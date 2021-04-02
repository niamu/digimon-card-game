(ns dcg.ingest
  (:gen-class)
  (:refer-clojure :exclude [frequencies])
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clj-http.client :as client]
   [datalevin.core :as d]
   [hickory.core :as hickory]
   [hickory.select :as select])
  (:import
   [java.awt Color]
   [java.util UUID]
   [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(def schema
  (reduce (fn [accl m]
            (assoc accl
                   (:db/ident m)
                   (dissoc m :db/ident)))
          {}
          [;; hash of card/number, card/parallel-id, and card/language
           {:db/ident :card/id
            :db/valueType :db.type/uuid
            :db/unique :db.unique/identity
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/number
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/language
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/level
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/inherited-effect
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/db
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/parallel-id
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/image
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/play-cost
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/form
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/security-effect
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/effect
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/keyword-effects
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}
           {:db/ident :card/timings
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}
           {:db/ident :card/frequencies
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}
           {:db/ident :card/mentions ;; Mentions of other `:card/id`s
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}
           {:db/ident :card/rarity
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/digivolve-conditions
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/many}
           {:db/ident :card/attribute
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/digimon-type
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/color
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/name
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/release
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :card/type
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           ;; Digivolve ID is hash of card number and index
           {:db/ident :digivolve/id
            :db/valueType :db.type/uuid
            :db/unique :db.unique/identity
            :db/cardinality :db.cardinality/one}
           {:db/ident :digivolve/index
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :digivolve/cost
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :digivolve/level
            :db/valueType :db.type/long
            :db/cardinality :db.cardinality/one}
           {:db/ident :digivolve/color
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           ;; Keyword Effects
           {:db/ident :keyword-effect/id
            :db/valueType :db.type/string
            :db/unique :db.unique/identity
            :db/cardinality :db.cardinality/one}
           {:db/ident :keyword-effect/lang
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           ;; Timings
           {:db/ident :timing/id
            :db/valueType :db.type/string
            :db/unique :db.unique/identity
            :db/cardinality :db.cardinality/one}
           {:db/ident :timing/lang
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}
           ;; Frequencies
           {:db/ident :frequency/id
            :db/valueType :db.type/string
            :db/unique :db.unique/identity
            :db/cardinality :db.cardinality/one}
           {:db/ident :frequency/lang
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one}]))

(def conn (d/get-conn "resources/db" schema))

(defn http-get*
  [url]
  (let [response (client/get url)]
    (condp = (:status response)
      200 response
      (throw (Exception. ^String (format "Request to %s failed with status %s"
                                         url
                                         (:status response)))))))

(defonce http-get (memoize http-get*))

(def subdomains
  [nil "en" "world"])

(defn base-url
  [& [{:keys [subdomain]}]]
  (str "https://" (cond->> "digimoncard.com"
                    subdomain (str subdomain "."))))

(def css-colors
  "RGB values of each card color"
  {:red [213 0 0]
   :blue [24 159 237]
   :yellow [240 172 9]
   :green [0 189 151]
   :white [153 153 153]
   :black [0 0 0]
   :purple [117 42 230]})

(def colors
  "RGB values of each card's digivolve color conditions"
  {:red [213 0 0]
   :blue [24 159 237]
   :yellow [240 172 9]
   :green [0 104 61]
   :white [153 153 153]
   :black [66 66 66]
   :purple [137 85 162]})

(def rarities
  {"C"   "Common"
   "U"   "Uncommon"
   "R"   "Rare"
   "SR"  "Super Rare"
   "SEC" "Secret Rare"
   "P"   "Promo"})

(def timings
  [{:timing/id "On Play"
    :timing/lang :en}
   {:timing/id "When Digivolving"
    :timing/lang :en}
   {:timing/id "When Attacking"
    :timing/lang :en}
   {:timing/id "End of Attack"
    :timing/lang :en}
   {:timing/id "On Deletion"
    :timing/lang :en}
   {:timing/id "Your Turn"
    :timing/lang :en}
   {:timing/id "All Turns"
    :timing/lang :en}
   {:timing/id "Opponent's Turn"
    :timing/lang :en}
   {:timing/id "Start of Your Turn"
    :timing/lang :en}
   {:timing/id "End of Your Turn"
    :timing/lang :en}
   {:timing/id "Security"
    :timing/lang :en}
   {:timing/id "Main"
    :timing/lang :en}
   {:timing/id "登場時"
    :timing/lang :ja}
   {:timing/id "進化時"
    :timing/lang :ja}
   {:timing/id "アタック時"
    :timing/lang :ja}
   {:timing/id "アタック終了時"
    :timing/lang :ja}
   {:timing/id "消滅時"
    :timing/lang :ja}
   {:timing/id "自分のターン"
    :timing/lang :ja}
   {:timing/id "お互いのターン"
    :timing/lang :ja}
   {:timing/id "相手のターン"
    :timing/lang :ja}
   {:timing/id "相手のターン終了時"
    :timing/lang :ja}
   {:timing/id "自分のターン開始時"
    :timing/lang :ja}
   {:timing/id "セキュリティ"
    :timing/lang :ja}
   {:timing/id "メイン"
    :timing/lang :ja}])

(def frequencies
  [{:frequency/id "Once Per Turn"
    :frequency/lang :en}
   {:frequency/id "Twice Per Turn"
    :frequency/lang :en}
   {:frequency/id "ターンに1回"
    :frequency/lang :ja}
   {:frequency/id "ターンに2回"
    :frequency/lang :ja}])

(defn keyword-effects
  [origin]
  (let [page (->> (http-get (str origin "/cardlist/"))
                  :body
                  hickory/parse
                  hickory/as-hickory)
        lang (->> page
                  (select/select
                   (select/descendant (select/tag "html")))
                  first
                  :attrs
                  :lang
                  keyword)
        effects (->> page
                     (select/select
                      (select/descendant (select/class "schItem")
                                         (select/tag :select)))
                     (map (comp #(mapcat :content %)
                                :content))
                     (filter (fn [kv]
                               (some #(string/includes? % "1") kv)))
                     first
                     rest
                     (map (fn [s]
                            (string/trim (string/replace s #"[0-9]" ""))))
                     set)]
    (reduce (fn [accl i]
              (conj accl {:keyword-effect/id i
                          :keyword-effect/lang lang}))
            []
            effects)))

(defn card
  [c]
  (let [header (->> (select/select (select/descendant
                                    (select/and (select/tag "ul")
                                                (select/class "cardinfo_head"))
                                    (select/tag "li"))
                                   c)
                    (map (comp first :content)))
        number (nth header 0)
        dl (fn [class-name]
             (select/select (select/descendant
                             (select/class class-name)
                             (select/or
                              (select/tag "dt")
                              (select/tag "dd")))
                            c))
        info-top (->> (dl "cardinfo_top_body")
                      (map (comp string/trim first :content))
                      (apply hash-map))
        info-bottom (->> (dl "cardinfo_bottom")
                         (drop-last 2)
                         (map #(let [content (first (:content %))]
                                 (cond->> content
                                   (string? content)
                                   string/trim
                                   (map? content)
                                   ((constantly :error)))))
                         (apply hash-map))
        color (-> (get-in c [:attrs :class])
                  (string/split #"card_detail_")
                  second
                  keyword)
        play-cost (some->> (or (get info-top "Play Cost")
                               (get info-top "登場コスト"))
                           (re-find #"[0-9]+")
                           Integer/parseInt)
        digivolve-conditions
        (->> [(or (get info-top "Digivolve Cost 1")
                  (get info-top "Digivolve 1 Cost")
                  (get info-top "進化コスト1"))
              (or (get info-top "Digivolve Cost 2")
                  (get info-top "Digivolve 2 Cost")
                  (get info-top "進化コスト2"))]
             (remove (fn [s] (= s "-")))
             (remove nil?)
             (map-indexed (fn [i c]
                            (when (or (string/includes? c "from")
                                      (string/includes? c "から"))
                              (let [lang (if (string/includes? c "from")
                                           :en
                                           :ja)
                                    [cost level]
                                    (cond-> c
                                      (= lang :en)
                                      (string/split #"from")
                                      (= lang :ja)
                                      (as-> x
                                          (-> (string/split x #"から")
                                              reverse)))
                                    level
                                    (some-> level
                                            string/lower-case
                                            (string/replace #"lv\.?([0-9]+)"
                                                            "$1")
                                            string/trim
                                            Long/parseLong)
                                    cost (some-> cost
                                                 string/trim
                                                 Long/parseLong)]
                                {:digivolve/id (-> (hash [number i])
                                                   str
                                                   (.getBytes)
                                                   UUID/nameUUIDFromBytes)
                                 :digivolve/index (inc i)
                                 :digivolve/cost cost
                                 :digivolve/level level
                                 :digivolve/color color})))))
        digivolve-conditions (->> (if (empty? digivolve-conditions)
                                    nil
                                    digivolve-conditions)
                                  (remove nil?))
        image (->> (select/select (select/descendant (select/tag "img")) c)
                   first
                   (#(-> (get-in % [:attrs :src])
                         (string/replace #"^.." ""))))]
    {:card/number number
     :card/rarity (nth header 1)
     :card/type (nth header 2)
     :card/level (some->> (nth header 3 nil)
                          (re-find #"[0-9]+")
                          Integer/parseInt)
     :card/play-cost (cond
                       (and (nil? play-cost)
                            (= (nth header 2)
                               (or "Option"
                                   "オプション"))) 0
                       :else play-cost)
     :card/digivolve-conditions digivolve-conditions
     :card/dp (some->> (get info-top "DP")
                       (re-find #"[0-9]+")
                       Integer/parseInt)
     :card/form (or (get info-top "Form")
                    (get info-top "形態"))
     :card/attribute (or (get info-top "Attribute")
                         (get info-top "属性"))
     :card/digimon-type (or (get info-top "Type")
                            (get info-top "タイプ"))
     :card/effect (or (get info-bottom "Effect")
                      (get info-bottom "効果"))
     :card/inherited-effect (or (get info-bottom "Digivoluve effect")
                                (get info-bottom "Digivolve effect")
                                (get info-bottom "進化元効果"))
     :card/security-effect (or (get info-bottom "Security effect")
                               (get info-bottom "セキュリティ効果"))
     :card/color color
     :card/name (->> (select/select
                      (select/descendant
                       (select/and (select/tag "div")
                                   (select/class "card_name")))
                      c)
                     first
                     ((comp first :content)))
     :card/image image}))

(defn cardlist
  [url]
  (let [page (->> (http-get url)
                  :body
                  hickory/parse
                  hickory/as-hickory)
        lang (->> page
                  (select/select
                   (select/descendant (select/tag "html")))
                  first
                  :attrs
                  :lang
                  keyword)
        article (->> page
                     (select/select
                      (select/descendant (select/id "article")))
                     first)
        title (->> (select/select (select/descendant
                                   (select/id "maintitle")
                                   (select/tag "h3"))
                                  article)
                   first
                   :content
                   first
                   string/trim)
        cards (select/select (select/descendant
                              (select/id "article")
                              (select/class "image_lists")
                              (select/class "popup")
                              (select/class "card_detail"))
                             article)]
    (reduce (fn [accl c]
              (let [nc (card c)
                    broken-keys (reduce (fn [broken-keys [k v]]
                                          (cond-> broken-keys
                                            (= v :error) (conj k)))
                                        nc)
                    cards-by-id (group-by :card/number accl)
                    nc (->> (select-keys (get-in cards-by-id
                                                 [(:card/number nc)
                                                  0])
                                         broken-keys)
                            (merge nc)
                            (reduce (fn [accl2 [k v]]
                                      (assoc accl2
                                             k
                                             (condp = v
                                               "ー" nil
                                               "－" nil
                                               "-" nil
                                               v)))
                                    {}))
                    parallel-id (or (some-> (re-find #"_P([0-9]+)"
                                                     (:card/image nc))
                                            second
                                            Long/parseLong)
                                    0)]
                (conj accl
                      (-> nc
                          (assoc :card/id (-> (hash [(:card/number nc)
                                                     parallel-id
                                                     lang])
                                              str
                                              (.getBytes)
                                              UUID/nameUUIDFromBytes)
                                 :card/parallel-id parallel-id
                                 :card/lang lang
                                 :card/release title)
                          (update :card/image
                                  #(str (string/replace url #"(\.com).*" "$1")
                                        %))))))
            []
            cards)))

(defn cardlists
  [origin]
  (let [response (http-get (str origin "/cardlist/"))]
    (->> (:body response)
         hickory/parse
         hickory/as-hickory
         (select/select
          (select/descendant (select/id "snaviListCol")
                             (select/tag "li")
                             (select/tag "a")))
         (pmap #(str origin "/cardlist/" (get-in % [:attrs :href])))
         (mapcat cardlist))))

(defn- color-diff
  "Calculate the distance between two colors"
  [[r1 g1 b1] [r2 g2 b2]]
  (+ (Math/pow (- r1 r2) 2)
     (Math/pow (- g1 g2) 2)
     (Math/pow (- b1 b2) 2)))

(defn digivolve-color
  [[r g b]]
  (->> (reduce (fn [accl c]
                 (assoc accl
                        (color-diff (second c)
                                    [r g b])
                        (first c)))
               {}
               colors)
       (sort-by key)
       (map val)
       first))

(defn digivolve-colors
  [{:card/keys [digivolve-conditions image] :as card}]
  (with-open [card-stream (io/input-stream (str "resources/images/" image))]
    (let [card-image (ImageIO/read card-stream)
          coords {0 [37 138]
                  1 [37 185]}
          color-pick (fn [[x y]]
                       (let [c (Color. (.getRGB card-image x y))]
                         [(.getRed c) (.getGreen c) (.getBlue c)]))]
      (assoc card
             :card/digivolve-conditions
             (->> (map-indexed (fn [i d]
                                 (assoc d
                                        :digivolve/color
                                        (-> (color-pick (get coords i))
                                            digivolve-color)))
                               digivolve-conditions)
                  (into []))))))

(defn save-card-image
  [{:card/keys [number lang image] :as c}]
  (let [filename (subs image (inc (string/last-index-of image "/")))
        translated? (string/starts-with? image "https://en.digimoncard.com")
        lang (if translated? :ja lang)
        lang (name lang)]
    (.mkdirs (io/file (str "resources/images/" lang)))
    (when-not (.exists (io/file (str "resources/images/" lang "/" filename)))
      (with-open [in (io/input-stream image)
                  out (io/output-stream (str "resources/images/"
                                             lang "/" filename))]
        (println (format "Downloading image: %s" image))
        (io/copy in out)))
    (assoc c :card/image (str lang "/" filename))))

(defn tag-card-mentions
  [cards c]
  (->> (filter (fn [{:keys [card/name]}]
                 (string/includes? (->> c
                                        ((comp string/join
                                               (juxt :card/effect
                                                     :card/security-effect
                                                     :card/inherited-effect)))
                                        string/join)
                                   name))
               cards)
       (reduce (fn [accl {:keys [card/number card/lang]}]
                 (conj accl
                       ;; Always add the original card for mentions
                       [:card/id (-> (hash [number 0 lang])
                                     str
                                     (.getBytes)
                                     UUID/nameUUIDFromBytes)]))
               [])
       (assoc c :card/mentions)))

(defn tag-card-timings
  [timings c]
  (->> (filter (fn [{:keys [timing/id]}]
                 (string/includes? (->> c
                                        ((comp string/join
                                               (juxt :card/effect
                                                     :card/security-effect
                                                     :card/inherited-effect)))
                                        string/join)
                                   id))
               timings)
       set
       (reduce (fn [accl {:keys [timing/id]}]
                 (conj accl [:timing/id id]))
               [])
       (assoc c :card/timings)))

(defn tag-card-keyword-effects
  [effects c]
  (->> (filter (fn [{:keys [keyword-effect/id]}]
                 (string/includes? (->> c
                                        ((comp string/join
                                               (juxt :card/effect
                                                     :card/security-effect
                                                     :card/inherited-effect)))
                                        string/join)
                                   id))
               effects)
       set
       (reduce (fn [accl {:keys [keyword-effect/id]}]
                 (conj accl [:keyword-effect/id id]))
               [])
       (assoc c :card/keyword-effects)))

(defn tag-card-frequencies
  [frequencies c]
  (->> (filter (fn [{:keys [frequency/id]}]
                 (string/includes? (->> c
                                        ((comp string/join
                                               (juxt :card/effect
                                                     :card/security-effect
                                                     :card/inherited-effect)))
                                        string/join)
                                   id))
               frequencies)
       set
       (reduce (fn [accl {:keys [frequency/id]}]
                 (conj accl [:frequency/id id]))
               [])
       (assoc c :card/frequencies)))

(defn -main
  []
  (let [effects (mapcat (fn [subdomain]
                          (keyword-effects (base-url {:subdomain subdomain})))
                        subdomains)
        cards (mapcat (fn [subdomain]
                        (cardlists (base-url {:subdomain subdomain})))
                      subdomains)
        cards (->> cards
                   (pmap (fn [c]
                           (->> (save-card-image c)
                                digivolve-colors
                                (tag-card-timings timings)
                                (tag-card-keyword-effects effects)
                                (tag-card-frequencies frequencies)
                                (reduce (fn [accl [k v]]
                                          (if (or (nil? v)
                                                  (and (coll? v)
                                                       (empty? v)))
                                            accl
                                            (assoc accl k v)))
                                        {})))))
        ;; We only store the Japanese digivolve costs as they are accurate
        digivolve-conditions
        (->> cards
             (filter #(and (= :ja (:card/lang %))
                           (zero? (:card/parallel-id %))
                           (not (empty? (:card/digivolve-conditions %)))))
             (mapcat :card/digivolve-conditions))
        cards (pmap (fn [{:card/keys [number digivolve-conditions] :as c}]
                      (cond-> c
                        (empty? digivolve-conditions)
                        (dissoc :card/digivolve-conditions)
                        (not-empty digivolve-conditions)
                        (assoc :card/digivolve-conditions
                               (map (fn [{:digivolve/keys [id]}]
                                      [:digivolve/id id])
                                    digivolve-conditions))))
                    cards)]
    (d/transact! conn (concat timings
                              frequencies
                              effects
                              digivolve-conditions
                              cards))
    (d/transact! conn (map (fn [c] (tag-card-mentions cards c)) cards))))
