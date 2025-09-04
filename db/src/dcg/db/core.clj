(ns dcg.db.core
  (:gen-class)
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.db.card :as card]
   [dcg.db.card.assertion :as assertion]
   [dcg.db.card.btcg-plus :as btcg-plus]
   [dcg.db.card.errata :as errata]
   [dcg.db.card.faq :as faq]
   [dcg.db.card.highlight :as highlight]
   [dcg.db.card.limitation :as limitation]
   [dcg.db.card.release :as release]
   [dcg.db.card.repair :as repair]
   [dcg.db.card.rule :as rule]
   [dcg.db.db :as db]
   [taoensso.timbre :as logging]))

(def origins
  [{:origin/url "https://digimoncard.com"
    :origin/language "ja"}
   #_{:origin/url "https://en.digimoncard.com"
      :origin/language "en"
      :origin/card-image-language "ja"}
   {:origin/url "https://world.digimoncard.com"
    :origin/language "en"}
   {:origin/url "https://www.digimoncard.cn"
    :origin/language "zh-Hans"}
   {:origin/url "https://digimoncard.co.kr"
    :origin/language "ko"}])

(defn releases-per-origin
  []
  (let [r (doall (pmap release/releases origins))]
    (assert (every? seq r) "Not every origin has releases")
    (assert (every? (fn [releases]
                      (->> releases
                           (every? (fn [{:release/keys [image-uri]}]
                                     (not image-uri)))))
                    r)
            (str "Not every release matched with a product. "
                 "Consider refreshing Korean product listing."))
    r))

(defn process-cards
  []
  (let [releases-per-origin (releases-per-origin)
        cards-per-origin (->> releases-per-origin
                              (pmap (fn [r]
                                      (->> r
                                           (filter :release/cardlist-uri)
                                           (mapcat card/cards-in-release)
                                           (remove nil?)
                                           (card/post-processing-per-origin r)
                                           card/consolidate-duplicate-images)))
                              doall)
        unrefined-cards (->> cards-per-origin
                             (reduce (fn [accl cards-in-origin]
                                       (->> (reduce (fn [accl2 {:card/keys [id]
                                                               :as card}]
                                                      (assoc accl2 id card))
                                                    {}
                                                    cards-in-origin)
                                            (merge-with merge accl)))
                                     {})
                             vals
                             repair/ace-names
                             card/image-processing)
        bandai-tcg-plus-mapping (btcg-plus/mapping unrefined-cards)
        limitations (->> (pmap limitation/limitations origins)
                         doall
                         (apply merge-with merge))
        faqs (->> (pmap faq/faqs origins)
                  doall
                  (apply merge-with merge))
        errata (->> (pmap errata/errata origins)
                    doall
                    (apply merge-with merge))
        common-values-by-number
        (reduce (fn [accl {:card/keys [number parallel-id language] :as card}]
                  (if (and (= language "ja")
                           (zero? parallel-id))
                    (assoc accl
                           number
                           (select-keys card
                                        [:card/color
                                         :card/rarity
                                         :card/level
                                         :card/dp
                                         :card/play-cost
                                         :card/use-cost
                                         :card/digivolution-requirements]))
                    accl))
                {}
                unrefined-cards)
        cards-with-errata-and-limitations
        (pmap (fn [{:card/keys [id number language] :as card}]
                (let [card-limitations (or (get-in limitations [number language])
                                           (get-in limitations [number :default]))
                      {:errata/keys [error correction]
                       :as errata-for-card} (get-in errata [number language])
                      titles-re #"(?i)((Inherited|Security)\s)?Effect"
                      errors (some-> error
                                     (string/replace "…" "")
                                     string/trim
                                     string/split-lines
                                     (as-> #__ coll
                                       (map string/trim coll)))
                      errors-by-attribute
                      (when (some #(re-matches titles-re %) errors)
                        (->> errors
                             (partition-by #(re-matches titles-re %))
                             (apply concat)
                             (partition-all 2)
                             (map (fn [[attribute s]]
                                    {(keyword "card"
                                              (-> attribute
                                                  string/lower-case
                                                  (string/replace #"\s+" "-")))
                                     s}))
                             (apply merge)))
                      errors (remove #(re-matches titles-re %) errors)
                      corrections (some-> correction
                                          (string/replace "…" "")
                                          string/trim
                                          string/split-lines
                                          (as-> #__ coll
                                            (->> coll
                                                 (map string/trim)
                                                 (remove #(re-matches titles-re %)))))]
                  (cond-> (merge (get common-values-by-number number)
                                 card
                                 (select-keys (get common-values-by-number number)
                                              [:card/digivolution-requirements]))
                    (= language "ko")
                    (merge (select-keys (get common-values-by-number number)
                                        [:card/use-cost]))
                    errata-for-card
                    (as-> #__ c
                      (-> (reduce-kv
                           (fn [m k v]
                             (let [error-indexes
                                   (some->> errors
                                            (map-indexed (fn [idx s]
                                                           [idx s])))
                                   [error-index error]
                                   (some->> error-indexes
                                            (filter (fn [[_ s]]
                                                      (string/includes? v s)))
                                            first)
                                   correction (and error-index
                                                   (nth corrections
                                                        error-index
                                                        nil))]
                               (cond-> m
                                 (if errors-by-attribute
                                   (and (string? v)
                                        error
                                        correction
                                        (string/includes? v error)
                                        (not (string/includes? v correction))
                                        (= (get errors-by-attribute k)
                                           error))
                                   (and (string? v)
                                        error
                                        correction
                                        (string/includes? v error)
                                        (not (string/includes? v correction))))
                                 (assoc k
                                        (string/replace v
                                                        error
                                                        correction)))))
                           c
                           c)
                          (assoc :card/errata errata-for-card)))
                    (seq card-limitations)
                    (assoc :card/limitations card-limitations)
                    (get bandai-tcg-plus-mapping id)
                    (assoc :card/bandai-tcg+
                           (get bandai-tcg-plus-mapping id)))))
              unrefined-cards)
        rule-revisions (->> (pmap rule/rules origins)
                            doall
                            (apply merge-with merge))
        cards
        (->> cards-with-errata-and-limitations
             (map (fn [{:card/keys [language number] :as card}]
                    (let [{:keys [before after]
                           :as rule-rev} (get-in rule-revisions
                                                 [number language])
                          card-faqs (get-in faqs [number language])]
                      (cond-> card
                        rule-rev
                        (update :card/effect
                                (fn [s]
                                  (if (or (string/includes? after "〈ルール〉")
                                          (string/includes? after "⟨Rule⟩")
                                          (string/includes? after "\u3008规则\u3009")
                                          (string/includes? after "〈룰〉"))
                                    (-> s
                                        (string/replace #"\s*\(Rule\)" "⟨Rule⟩")
                                        (string/replace "<规则>" "\u3008规则\u3009")
                                        (string/replace after "")
                                        (string/replace before "")
                                        string/trim
                                        (str "\n" after))
                                    (string/replace s before after))))
                        (seq card-faqs)
                        (assoc :card/faqs
                               (map-indexed (fn [idx faq]
                                              (assoc faq :faq/id
                                                     (format "faq/%s_%s_%d"
                                                             language
                                                             number
                                                             idx)))
                                            card-faqs))))))
             rule/process-rules
             highlight/process-highlights)]
    (->> cards
         (filter (fn [{{:image/keys [path]} :card/image}]
                   (io/resource (subs path 1))))
         (sort-by :card/id))))

(defn generate-phash-db!
  []
  (->> (db/q '{:find [?hash ?language ?number ?parallel-id]
               :where [[?c :card/number ?number]
                       [?c :card/language ?language]
                       [?c :card/parallel-id ?parallel-id]
                       [?c :card/image ?i]
                       [?i :image/hash ?hash]]})
       (map (fn [[hash language number parallel-id]]
              [(str hash) language number parallel-id]))
       json/write-str
       (spit (io/file "resources/phash_db.json"))))

(defn ingest
  [& _args]
  (logging/info "DB ingestion started...")
  (->> (process-cards)
       assertion/card-assertions
       db/transact!)
  (logging/info "DB ingestion completed."))

(defn -main
  [& _args]
  (db/export!)
  (generate-phash-db!))

(comment
  (set! *print-namespace-maps* false)

  (def *cards (time (process-cards)))

  (->> *cards
       assertion/card-assertions
       db/transact!)

  )
