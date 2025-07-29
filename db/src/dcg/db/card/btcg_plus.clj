(ns dcg.db.card.btcg-plus
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [dcg.db.card.cv :as cv]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [taoensso.timbre :as logging])
  (:import
   [java.security MessageDigest]))

(def ^:private domain "https://api.bandai-tcg-plus.com")

(defn- sha1
  [s]
  (let [hash-bytes (-> (MessageDigest/getInstance "SHA-1")
                       (.digest (.getBytes s)))
        sb (StringBuilder.)]
    (doseq [b hash-bytes]
      (.append sb (format "%02x" b)))
    (.toString sb)))

(def ^:private char->base64
  (reduce (fn [accl char]
            (assoc accl char (count accl)))
          {}
          (concat (map char (concat (range 48 58)
                                    (range 65 91)
                                    (range 97 123)))
                  [\+ \/])))

(defn card-code->id
  [card-code]
  (->> card-code
       reverse
       (map-indexed (fn [idx char] [idx char]))
       (reduce (fn [accl [idx char]]
                 (+ accl
                    (* (int (Math/pow 64 idx))
                       (get char->base64 char))))
               0)))

(defn id->card-code
  [id]
  (loop [n id
         x ""]
    (if (pos? n)
      (recur (quot n 64)
             (str (get (set/map-invert char->base64)
                       (mod n 64))
                  x))
      x)))

(defn- download-image!
  [{:card/keys [number language] {:image/keys [source]} :card/image :as card}]
  (let [ext (string/lower-case (re-find #"\.\S{3,4}$" source))
        filename (str (sha1 source) ext)
        path (format "resources/images/btcg-cards/%s/%s/%s"
                     language number filename)]
    (when-not (.exists (io/file path))
      (when-let [image-bytes (some-> source
                                     (utils/as-bytes {})
                                     card-utils/trim-transparency!
                                     card-utils/trim-white-border!)]
        (.mkdirs (io/file (.getParent (io/file path))))
        (with-open [in (io/input-stream image-bytes)
                    out (io/output-stream path)]
          (io/copy in out))
        (logging/info (format "Downloaded image: %s" source))))
    (assoc-in card [:card/image :image/path]
              (string/replace path #"^resources" ""))))

(defn- origin-releases
  [id]
  (let [{:strs [game_formats
                card_set_list]} (-> (utils/http-get (str domain
                                                         "/api/user/card")
                                                    {:query-params
                                                     {"game_title_id" id}})
                                    json/read-str
                                    (get-in ["success"]))]
    {:origin/game-formats (map (fn [{:strs [game_format_id format_name]}]
                                 {:format/id game_format_id
                                  :format/name format_name})
                               game_formats)
     :origin/releases (reduce (fn [accl {:strs [id name]}]
                                (assoc accl id name))
                              {}
                              card_set_list)}))

(defn- origins
  []
  (-> (utils/http-get (str domain "/api/masterdata"))
      json/read-str
      (get-in ["success" "game_title_not_sort"])
      vals
      (as-> #__ games
        (->> games
             (filter (fn [{:strs [title]}]
                       (= title "DIGIMON CARD GAME")))
             (map (fn [{:strs [id language_code]}]
                    (merge {:origin/id id
                            :origin/language (string/lower-case language_code)}
                           (origin-releases id))))))))

(defn- cards-per-origin
  [{:origin/keys [id language releases]}]
  (loop [offset 0
         all-cards []]
    (let [limit 28
          {:strs [total cards]} (-> (utils/http-get (str domain
                                                         "/api/user/card/list")
                                                    {:query-params
                                                     {"game_title_id" id
                                                      "limit" limit
                                                      "offset" offset}})
                                    json/read-str
                                    (get "success"))
          total (parse-long total)
          cards (->> cards
                     (pmap (fn [{:strs [id card_number card_set_id image_url]}]
                             (-> {:card/number card_number
                                  :card/bandai-tcg+ id
                                  :card/language language
                                  :card/image {:image/source image_url}
                                  :card/notes (get releases card_set_id)}
                                 download-image!))))]
      (if (< (+ (count all-cards)
                (count cards))
             total)
        (recur (+ offset limit)
               (concat all-cards cards))
        (concat all-cards cards)))))

(defonce ^:private ingest
  (memoize
   (fn []
     (logging/info "Bandai TCG+ ingestion started...")
     (let [result (->> (origins)
                       (mapcat cards-per-origin)
                       doall)]
       (logging/info "Bandai TCG+ ingestion completed.")
       result))))

(defn mapping
  "Provided an input of all cards, this function will return a map
  where keys are the :card/id and vals are the Bandai TCG+ ID"
  [cards]
  (let [bandai-tcg-cards-lookup (->> (ingest)
                                     (group-by :card/language)
                                     (reduce-kv (fn [m language cards]
                                                  (assoc m language
                                                         (group-by :card/number
                                                                   cards)))
                                                {}))
        cards-lookup (->> cards
                          (group-by :card/language)
                          (reduce-kv (fn [m language cards]
                                       (assoc m language
                                              (group-by :card/number cards)))
                                     {}))]
    (->> cards-lookup
         (reduce-kv
          (fn [accl language cards-by-number]
            (cond-> accl
              (get bandai-tcg-cards-lookup language)
              (merge (reduce-kv
                      (fn [m number cards]
                        (let [tcg-cards
                              (->> (get-in bandai-tcg-cards-lookup
                                           [language number])
                                   (map (fn [card]
                                          (assoc-in card
                                                    [:card/image :image/hash]
                                                    (cv/image-hash card)))))
                              matched
                              (reduce
                               (fn [xs {:card/keys [id] :as card}]
                                 (let [hash (cv/image-hash card)
                                       bandai-tcg-id
                                       (when hash
                                         (->> tcg-cards
                                              (filter (fn [{{image-hash :image/hash}
                                                           :card/image}]
                                                        image-hash))
                                              (map (fn [{{image-hash :image/hash
                                                         :image/keys [path]}
                                                        :card/image
                                                        :as c}]
                                                     [(.bitCount
                                                       (.xor hash image-hash))
                                                      (get c :card/bandai-tcg+)]))
                                              (remove (fn [[distance _]]
                                                        (> distance 11)))
                                              sort
                                              first
                                              second))]
                                   (if bandai-tcg-id
                                     (assoc xs id bandai-tcg-id)
                                     xs)))
                               {}
                               cards)]
                          (cond-> m
                            (and tcg-cards
                                 (seq matched))
                            (merge matched))))
                      {}
                      cards-by-number))))
          {}))))

(comment
  (defonce bandai-tcg-cards (ingest))

  (mapping dcg.db.core/*cards)

  (let [tcg-cards (->> bandai-tcg-cards
                       (group-by :card/language)
                       (reduce-kv (fn [m language cards]
                                    (assoc m language
                                           (group-by :card/number cards)))
                                  {}))
        cards (->> dcg.db.core/*cards
                   (group-by :card/language)
                   (reduce-kv (fn [m language cards]
                                (assoc m language
                                       (group-by :card/number cards)))
                              {}))]
    (->> cards
         (reduce-kv
          (fn [accl language cards-by-number]
            (let [tcg-cards-by-number (get tcg-cards language)]
              (cond-> accl
                tcg-cards-by-number
                (assoc language
                       (reduce-kv
                        (fn [m number cards]
                          (let [tcg-cards
                                (->> (get tcg-cards-by-number
                                          number)
                                     (map (fn [card]
                                            (assoc-in card
                                                      [:card/image :image/hash]
                                                      (cv/image-hash card)))))
                                not-matched
                                (reduce (fn [xs {:card/keys [id] :as card}]
                                          (let [hash (cv/image-hash card)]
                                            (if (nil? (->> tcg-cards
                                                           (map (fn [{{image-hash :image/hash
                                                                       :image/keys [path]} :card/image}]
                                                                  (.bitCount (.xor hash image-hash))))
                                                           (remove (fn [distance]
                                                                     (> distance 11)))
                                                           sort
                                                           first))
                                              (conj xs id)
                                              xs)))
                                        []
                                        cards)]
                            (cond-> m
                              (and tcg-cards
                                   (seq not-matched))
                              (assoc number not-matched))))
                        {}
                        cards-by-number)))))
          {})
         vals
         (mapcat vals)
         flatten
         sort))

  (let [language "en"
        number "ST2-07"
        tcg-cards (->> bandai-tcg-cards
                       (group-by :card/language)
                       (reduce-kv (fn [m language cards]
                                    (assoc m language
                                           (group-by :card/number cards)))
                                  {}))
        cards (->> dcg.db.core/*cards
                   (group-by :card/language)
                   (reduce-kv (fn [m language cards]
                                (assoc m language
                                       (group-by :card/number cards)))
                              {}))]
    (->> (get-in cards [language number])
         (map (fn [{:card/keys [id] :as card}]
                (let [hash (cv/image-hash card)]
                  {id (->> (get-in tcg-cards [language number])
                           (map (fn [card]
                                  (assoc-in card
                                            [:card/image :image/hash]
                                            (cv/image-hash card))))
                           (sort-by (fn [{{image-hash :image/hash} :card/image}]
                                      (.bitCount (.xor hash image-hash))))
                           first
                           ((juxt :card/bandai-tcg+
                                  (fn [{{image-hash :image/hash} :card/image}]
                                    (.bitCount (.xor hash image-hash)))
                                  (comp :image/path :card/image))))})))
         (apply merge)))

  (let [language "ja"
        tcg-cards (->> bandai-tcg-cards
                       (group-by :card/language)
                       (reduce-kv (fn [m language cards]
                                    (assoc m language
                                           (group-by :card/number cards)))
                                  {}))
        cards (->> dcg.db.core/*cards
                   (filter (fn [card]
                             (= (:card/language card) language)))
                   (group-by :card/number))
        discrepancies
        (->> cards
             (reduce-kv (fn [m number cards]
                          (if (and (< (count cards)
                                      (->> (get-in tcg-cards [language number])
                                           (map :card/image)
                                           set
                                           count))
                                   (not= (->> cards
                                              (mapcat
                                               (comp string/split-lines
                                                     #(string/replace %
                                                                      "、" "\n")
                                                     :card/notes))
                                              set)
                                         (->> (get-in tcg-cards [language number])
                                              (mapcat
                                               (comp string/split-lines
                                                     #(string/replace %
                                                                      "、" "\n")
                                                     :card/notes))
                                              set)))
                            (conj m number)
                            m))
                        [])
             sort)]
    (->> discrepancies
         (mapcat (fn [number]
                   (let [hashes (->> (get cards number)
                                     (map cv/image-hash))]
                     (->> (get-in tcg-cards [language number])
                          (map (fn [card]
                                 (assoc-in card
                                           [:card/image :image/hash]
                                           (cv/image-hash card))))
                          (remove (fn [{{:image/keys [hash]} :card/image}]
                                    (some (fn [db-hash]
                                            (<= (.bitCount
                                                 (.xor ^BigInteger hash db-hash))
                                                5))
                                          hashes)))))))))

  (let [deck "Jq/.Jr0.Jr1!Jr2!Jr4!"
        deck-parts (string/split deck #"!")
        [main sideboard digi-eggs]
        (->> deck-parts
             (map (fn [cards]
                    (->> (string/split cards #"\.")
                         (map card-code->id)))))]
    {:deck/digi-eggs digi-eggs
     :deck/deck main
     :deck/sideboard sideboard
     :deck/icon ""
     :deck/name ""})

  )
