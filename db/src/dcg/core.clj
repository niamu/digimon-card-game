(ns dcg.core
  (:gen-class)
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [dcg.card :as card]
   [dcg.card.assertion :as assertion]
   [dcg.card.errata :as errata]
   [dcg.card.highlight :as highlight]
   [dcg.card.limitation :as limitation]
   [dcg.card.release :as release]
   [dcg.card.translation :as translation]
   [dcg.db :as db]))

(def origins
  [{:origin/url "https://digimoncard.com"
    :origin/language "ja"}
   {:origin/url "https://en.digimoncard.com"
    :origin/language "en"
    :origin/card-image-language "ja"}
   {:origin/url "https://world.digimoncard.com"
    :origin/language "en"}
   {:origin/url "https://www.digimoncard.cn"
    :origin/language "zh-Hans"}
   {:origin/url "https://digimoncard.co.kr"
    :origin/language "ko"}])

(def releases-per-origin
  (memoize (fn []
             (pmap release/releases origins))))

(defn process-cards
  []
  (let [releases-per-origin (releases-per-origin)
        cards-per-origin
        (pmap (fn [releases]
                (->> releases
                     (filter :release/cardlist-uri)
                     (mapcat card/cards-in-release)
                     (card/post-processing-per-origin releases)))
              releases-per-origin)
        tr-map (translation/card-name-replacement-map cards-per-origin)
        unrefined-cards (->> cards-per-origin
                             (reduce (fn [accl cards-in-origin]
                                       (->> (reduce (fn [accl card]
                                                      (assoc accl
                                                             (:card/id card)
                                                             card))
                                                    {}
                                                    cards-in-origin)
                                            (merge-with merge accl)))
                                     {})
                             vals
                             card/image-processing)
        limitations (->> (pmap limitation/limitations origins)
                         (apply merge-with merge))
        errata (->> (pmap errata/errata origins)
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
        (pmap (fn [{:card/keys [number language image] :as card}]
                (let [limitation (get-in limitations
                                         [number (:image/language image)])
                      {:errata/keys [error correction]
                       :as errata-for-card} (get-in errata
                                                    [number
                                                     (:image/language image)])
                      titles-re #"(?i)\s*\[?((Inherited|Security)\s)?Effect\]?\s+"
                      errors (some-> error
                                     (string/replace titles-re "\n")
                                     string/trim
                                     string/split-lines
                                     (as-> #__ coll
                                       (map string/trim coll)))
                      corrections (some-> correction
                                          (string/replace titles-re "\n")
                                          string/trim
                                          string/split-lines
                                          (as-> #__ coll
                                            (map string/trim coll)))]
                  (cond-> (merge (get common-values-by-number number)
                                 card
                                 (select-keys (get common-values-by-number number)
                                              [:card/digivolution-requirements]))
                    (= language "ko")
                    (merge (select-keys (get common-values-by-number number)
                                        [:card/use-cost]))
                    errata-for-card
                    (as-> #__ c
                      (-> (reduce-kv (fn [m k v]
                                       (let [error-indexes
                                             (some->> errors
                                                      (map-indexed (fn [idx s]
                                                                     [idx s])))
                                             [error-index error]
                                             (some->> error-indexes
                                                      (filter (fn [[idx s]]
                                                                (string/includes? v s)))
                                                      first)
                                             correction (and error-index
                                                             (nth corrections
                                                                  error-index
                                                                  nil))]
                                         (cond-> m
                                           (and (string? v) error correction)
                                           (assoc k
                                                  (string/replace v
                                                                  error
                                                                  correction)))))
                                     c
                                     c)
                          (assoc :card/errata errata-for-card)))
                    (and limitation
                         (not= (:limitiation/type limitation)
                               :unrestrict))
                    (assoc :card/limitation limitation))))
              unrefined-cards)
        {:keys [mentions
                treats
                highlights]} (highlight/all cards-with-errata-and-limitations)
        cards
        (pmap (fn [{:card/keys [id language] :as card}]
                (let [highlights (get-in highlights [id language])
                      aka (reduce-kv (fn [s k v]
                                       (string/replace s k v))
                                     (:card/name card)
                                     tr-map)
                      treats (get-in treats [id language])
                      treats (concat treats
                                     (when (not= aka (:card/name card))
                                       [{:treat/id (format "treat/%s_%s"
                                                           id
                                                           "aka")
                                         :treat/as aka
                                         :treat/field :card/name}]))
                      mentions (get-in mentions [id language])]
                  (cond-> card
                    highlights (assoc :card/highlights highlights)
                    (not (empty? treats)) (assoc :card/treats treats)
                    mentions (assoc :card/mentions mentions))))
              cards-with-errata-and-limitations)]
    (sort-by :card/id cards)))

(defn -main
  [& args]
  (->> (process-cards)
       assertion/card-assertions
       db/save-to-file!
       db/import!))

(comment
  (def *cards (time (process-cards)))

  (->> *cards
       assertion/card-assertions
       db/save-to-file!
       db/import!))
