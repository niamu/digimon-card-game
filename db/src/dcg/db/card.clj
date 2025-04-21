(ns dcg.db.card
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [dcg.db.card.cv :as cv]
   [dcg.db.card.repair :as repair]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.net URI]
   [java.util Date]))

(defn- download-image!
  [{:image/keys [id path source] :as image}]
  (let [filename (str "resources" path)]
    (when-not (.exists (io/file filename))
      (case (.getScheme source)
        "file" (with-open [in (io/input-stream source)
                           out (io/output-stream filename)]
                 (io/copy in out))
        (when-let [image-bytes (some-> (str source)
                                       (utils/as-bytes
                                        (utils/cupid-headers
                                         (str (.getScheme source)
                                              "://"
                                              (.getHost source))))
                                       card-utils/trim-transparency!)]
          (.mkdirs (io/file (.getParent (io/file filename))))
          (with-open [in (io/input-stream image-bytes)
                      out (io/output-stream filename)]
            (io/copy in out))))
      (logging/info (format "Downloaded image: [%s] %s" id (str source))))
    image))

(defn post-processing-per-origin
  [releases cards]
  (let [releases-by-set
        (reduce (fn [accl {:release/keys [name] :as release}]
                  (let [ver (re-find #"(?i)Ver\.?[0-9]\.[0-9]" name)
                        set-id (some-> (re-find
                                        (card-utils/within-brackets-re
                                         (get card-utils/text-punctuation
                                              :square-brackets))
                                        name)
                                       rest
                                       (as-> x (remove nil? x))
                                       first
                                       (string/replace "-" "")
                                       (string/replace #"0([0-9]+)" "$1"))]
                    (if set-id
                      (assoc accl (cond->> set-id
                                    ver (str ver)) release)
                      accl)))
                {}
                releases)
        release-set-ids (set (keys releases-by-set))]
    (->> cards
         (sort-by (juxt (fn [{:card/keys [number]}]
                          (string/replace number #"\-.*" ""))
                        (fn [{:card/keys [number]}]
                          (->> (re-seq #"[0-9]+" number)
                               (apply str)
                               parse-long))
                        (fn [{:card/keys [parallel-id image]}]
                          (if (nil? parallel-id)
                            (-> image
                                :image/source
                                utils/last-modified
                                inst-ms)
                            parallel-id))))
         (reduce (fn [accl {:card/keys [number language parallel-id
                                       notes release] :as card}]
                   (let [prev-card (peek accl)
                         release (dissoc release :release/card-image-language)
                         releases-in-notes
                         (some->> notes
                                  (re-seq (card-utils/within-brackets-re
                                           (get card-utils/text-punctuation
                                                :square-brackets)))
                                  (mapcat rest)
                                  (remove nil?)
                                  (map (fn [s]
                                         (let [ver (re-find #"(?i)Ver\.?[0-9]\.[0-9]"
                                                            notes)]
                                           (-> s
                                               (string/replace "-" "")
                                               (string/replace #"0([0-9]+)"
                                                               "$1")
                                               (cond->> #__
                                                 ver (str ver))))))
                                  (filter (fn [r]
                                            (contains? release-set-ids r)))
                                  (map releases-by-set)
                                  (sort-by (comp (fnil inst-ms
                                                       (Date. Long/MAX_VALUE))
                                                 :release/date))
                                  (map #(dissoc %
                                                :release/card-image-language
                                                :release/http-opts)))
                         existing-card-numbers (filter (fn [c]
                                                         (= (:card/number c)
                                                            number))
                                                       accl)
                         parallel-id (cond
                                       (and (= number
                                               (:card/number prev-card))
                                            (nil? parallel-id))
                                       (or (some-> prev-card
                                                   :card/parallel-id
                                                   inc)
                                           0)
                                       (and (= number
                                               (:card/number prev-card))
                                            (= parallel-id
                                               (:card/parallel-id prev-card)))
                                       (inc parallel-id)
                                       (seq existing-card-numbers)
                                       (-> existing-card-numbers
                                           last
                                           (get :card/parallel-id 0)
                                           inc)
                                       :else
                                       (or parallel-id 0))
                         card-id (format "card/%s_%s_P%s"
                                         language
                                         number
                                         (str parallel-id))
                         card (-> card
                                  (assoc :card/id card-id
                                         :card/parallel-id parallel-id)
                                  (update :card/image
                                          (fn [image]
                                            (assoc image :image/id
                                                   (string/replace card-id
                                                                   #"^card/"
                                                                   "image/")
                                                   :image/path
                                                   (format
                                                    "/images/cards/%s/%s.png"
                                                    (:image/language image)
                                                    (cond-> number
                                                      (not= parallel-id 0)
                                                      (str "_P"
                                                           parallel-id))))))
                                  (dissoc :card/release)
                                  (assoc :card/releases
                                         (if (empty? releases-in-notes)
                                           [release]
                                           releases-in-notes)))]
                     (conj accl card)))
                 []))))

(defn- reindex-parallel-ids
  [cards]
  (map-indexed (fn [idx {:card/keys [language number] :as card}]
                 (-> card
                     (assoc :card/id (format "card/%s_%s_P%s"
                                             language
                                             number
                                             (str idx)))
                     (assoc :card/parallel-id idx)
                     (assoc-in [:card/image :image/id]
                               (format
                                "/images/cards/%s/%s.png"
                                language
                                (cond-> number
                                  (not= idx 0)
                                  (str "_P" idx))))
                     (assoc-in [:card/image :image/path]
                               (format
                                "/images/cards/%s/%s.png"
                                language
                                (cond-> number
                                  (not= idx 0)
                                  (str "_P" idx))))))
               cards))

(defn consolidate-duplicate-images
  [cards]
  (->> cards
       (sort-by :card/id)
       (partition-by :card/number)
       (mapcat (fn [cards-for-number]
                 (let [sources (map (comp :image/source
                                          :card/image)
                                    cards-for-number)]
                   (if (= (count sources)
                          (count (set sources)))
                     cards-for-number
                     (->> cards-for-number
                          (group-by (comp :image/source
                                          :card/image))
                          vals
                          (mapcat
                           (fn [cards-per-source]
                             (if (> (count cards-per-source) 1)
                               [(reduce
                                 (fn [accl {:card/keys [notes releases]}]
                                   (-> accl
                                       (update :card/notes
                                               (fnil #(str % "\n"
                                                           notes) ""))
                                       (update :card/releases
                                               (fn [existing-releases]
                                                 (->> (set existing-releases)
                                                      (set/union (set releases))
                                                      (sort-by :release/id)
                                                      (into []))))))
                                 (first cards-per-source)
                                 (rest cards-per-source))]
                               cards-per-source)))
                          reindex-parallel-ids)))))))

(defn image-processing
  [cards]
  (doall (pmap (fn [{:card/keys [language image parallel-id] :as card}]
                 (cond-> (update card :card/image download-image!)
                   (and (= language "ja")
                        (zero? parallel-id)) cv/digivolution-requirements
                   (= language
                      (:image/language image)) cv/block-icon
                   true (as-> #_card c
                          (cond-> c
                            (:card/block-icon c) cv/supplemental-rarity))
                   (.exists (->> (:image/path image)
                                 (str "resources")
                                 io/file)) cv/image-roi-hash))
               cards)))

(defn- pack-type
  [{:release/keys [genre]} {:card/keys [notes number]}]
  (case genre
    "ブースターパック" :booster
    "Booster Packs"    :booster
    "补充包"           :booster
    "확장팩"           :booster
    "構築済みデッキ"   :starter
    "Starter Decks"    :starter
    "Advanced Decks"   :starter
    "基本卡组"         :starter
    "구축완료 덱"      :starter
    (if (and notes (re-find #"Box Promotion" notes))
      :box-topper
      (when (string/starts-with? number "P-")
        :promo))))

(defn- card
  [{:release/keys [language cardlist-uri card-image-language] :as release}
   dom-tree]
  (let [origin (str (.getScheme ^URI cardlist-uri) "://"
                    (.getHost ^URI cardlist-uri))
        header (->> (select/select (select/descendant
                                    (select/and (select/tag "ul")
                                                (select/class "cardinfo_head"))
                                    (select/tag "li"))
                                   dom-tree)
                    (map card-utils/text-content))
        number (-> (nth header 0)
                   (string/replace "EX5-43" "EX5-043")
                   ;; ko cards sometimes add a "P" suffix to the card number
                   (string/replace #"P$" "")
                   ;; ko cards sometimes have the name preceeded by a space
                   (string/replace #"\s.*" ""))
        category (or (some-> (nth header 2)
                             (string/replace #"(?i)digimon" "Digimon")
                             (string/replace "Opiton" "Option"))
                     "Unknown")
        alternate-art? (let [header-set (set header)]
                         (or (contains? header-set "パラレル")
                             (contains? header-set "Parallel Rare")
                             (contains? header-set "Alternative Art")
                             (contains? header-set "병렬")
                             (contains? header-set "페러렐")))
        rarity (string/replace (or (nth header 1) "P")
                               "Ｕ" "U")
        dl (fn [class-name]
             (select/select (select/descendant
                             (select/class class-name)
                             (select/or
                              (select/tag "dt")
                              (select/tag "dd")))
                            dom-tree))
        info-top
        (->> (dl "cardinfo_top_body")
             (map (comp repair/text-fixes
                        card-utils/normalize-string
                        card-utils/text-content))
             (apply hash-map))
        info-bottom
        (->> (dl "cardinfo_bottom")
             (drop-last 2)
             (map (comp repair/text-fixes
                        card-utils/normalize-string
                        card-utils/text-content))
             (apply hash-map))
        notes (->> (select/select (select/descendant
                                   (select/class "cardinfo_bottom")
                                   (select/follow-adjacent
                                    (select/find-in-text #"Notes|入手情報|입수 정보")
                                    (select/tag "dd")))
                                  dom-tree)
                   first
                   :content)
        colors (if (or (get info-top "色")
                       (get info-top "Color"))
                 (->> (select/select (select/descendant
                                      (select/and (select/tag "dd")
                                                  (select/class "cardColor"))
                                      (select/tag "span"))
                                     dom-tree)
                      (mapv (fn [{:keys [attrs]}]
                              (-> (:class attrs)
                                  (string/replace "cardColor_" "")
                                  keyword))))
                 (->> (get-in dom-tree [:attrs :class])
                      (re-seq (re-pattern (->> [:red
                                                :blue
                                                :yellow
                                                :green
                                                :black
                                                :purple
                                                :white]
                                               (map name)
                                               (string/join "|"))))
                      (mapv keyword)))
        play-cost (some->> (or (get info-top "Play Cost")
                               (get info-top "登場コスト")
                               (get info-top "등장 비용"))
                           (re-find #"[0-9]+")
                           parse-long)
        play-cost (cond
                    (and (nil? play-cost)
                         (or (= (nth header 2) "Option")
                             (= (nth header 2) "オプション")
                             (= (nth header 2) "옵션"))) 0
                    :else play-cost)
        level (some->> (nth header 3 nil)
                       (re-find #"[0-9]+")
                       parse-long)
        digivolution-requirements
        (->> (dl "cardinfo_top_body")
             (map (comp card-utils/normalize-string
                        string/trim
                        first
                        :content))
             (partition-all 2)
             (filter (fn [[k v]]
                       (and (or (string/includes? k "Digivolve")
                                (string/includes? k "進化コスト")
                                (string/includes? k "진화 비용"))
                            v)))
             (map second)
             (remove (fn [s] (or (empty? s) (= s "-"))))
             (remove nil?)
             (reduce (fn [accl c]
                       (if-let [c c]
                         (let [i (count accl)
                               from (or (some->> (string/lower-case c)
                                                 (re-find #"(?i).*lv\.?(\d+).*")
                                                 second
                                                 parse-long)
                                        (get (first accl) :digivolve/level))
                               cost (or (some->> (string/lower-case c)
                                                 (re-find #"(?i).*(?<!lv\.?)(\d+)")
                                                 second
                                                 parse-long)
                                        (get (first accl) :digivolve/cost))]
                           (conj accl
                                 (cond-> {:digivolve/id (format "digivolve/%s_index%d"
                                                                number i)
                                          :digivolve/index i
                                          :digivolve/cost cost
                                          :digivolve/color #{(nth colors i
                                                                  (first colors))}}
                                   from (assoc :digivolve/level from))))
                         accl))
                     []))
        digivolution-requirements
        (->> (if (empty? (remove nil? digivolution-requirements))
               nil
               digivolution-requirements))
        image-source (-> (select/select (select/descendant (select/tag "img"))
                                        dom-tree)
                         first
                         (get-in [:attrs :src])
                         (string/replace "//" "/")
                         (string/replace #"^\.\." ""))
        parallel-id (if (or alternate-art?
                            (re-find (re-pattern
                                      (format "/%s_P([0-9]+)" number))
                                     image-source))
                      (or (some-> (re-find (re-pattern
                                            (format "/%s_P([0-9]+)" number))
                                           image-source)
                                  second
                                  parse-long)
                          nil)
                      0)
        card-id (format "card/%s_%s_P%s"
                        language
                        number
                        (str parallel-id))
        dp (some->> (get info-top "DP")
                    (re-find #"[0-9]+")
                    parse-long)
        form (or (get info-top "Form")
                 (get info-top "形態")
                 (get info-top "형태"))
        attribute (or (get info-top "Attribute")
                      (get info-top "属性")
                      (get info-top "속성"))
        type (or (get info-top "Type")
                 (get info-top "タイプ")
                 (get info-top "유형"))
        [attribute type] (if (and (contains? #{"Variable"
                                               "Free"
                                               "Data"
                                               "Unknown"
                                               "Vaccine"
                                               "Virus"
                                               "NO DATA"} type)
                                  attribute)
                           [type attribute]
                           [attribute type])
        effect (some-> (or (get info-bottom "上段テキスト")
                           (get info-bottom "Effect")
                           (get info-bottom "Upper Text")
                           (get info-bottom "効果")
                           (get info-bottom "효과")
                           (get info-bottom "상단 텍스트"))
                       (string/replace "oLv." "olv")
                       (string/replace #"\n+" "\n"))
        inherited-effect (or (get info-bottom "下段テキスト")
                             (get info-bottom "Inherited Effect")
                             (get info-bottom "Digivolve effect")
                             (get info-bottom "Lower Text")
                             (get info-bottom "進化元効果")
                             (get info-bottom "진화원 효과")
                             (get info-bottom "하단 텍스트"))
        security-effect (or (get info-bottom "下段テキスト")
                            (get info-bottom "Security Effect")
                            (get info-bottom "Security effect")
                            (get info-bottom "Lower Text")
                            (get info-bottom "セキュリティ効果")
                            (get info-bottom "시큐리티 효과")
                            (get info-bottom "하단 텍스트"))
        repair-fn (-> repair/text-fixes-by-number-by-language
                      (get-in [number language]))]
    (cond-> {:card/id card-id
             :card/release (dissoc release :release/http-opts)
             :card/language language
             :card/number number
             :card/parallel-id parallel-id
             :card/rarity rarity
             :card/category category
             :card/color (->> colors
                              (map-indexed (fn [i color]
                                             {:color/id (format "color/%s_index%d"
                                                                number i)
                                              :color/index i
                                              :color/color (keyword color)}))
                              (into []))
             :card/name (->> (select/select
                              (select/descendant
                               (select/and (select/tag "div")
                                           (select/class "card_name")))
                              dom-tree)
                             first
                             ((comp card-utils/normalize-string
                                    string/trim
                                    (fn [s]
                                      (-> s
                                          (string/replace #"\s+" " ")
                                          (string/replace
                                           (re-pattern
                                            (format "%s\\s*(%s)?\\s*"
                                                    number
                                                    rarity))
                                           "")))
                                    first
                                    :content)))
             :card/image {:image/language card-image-language
                          :image/source (URI. (str origin image-source))}}
      play-cost (assoc (if (or (= category "オプション")
                               (= category "Option")
                               (= category "옵션"))
                         :card/use-cost
                         :card/play-cost) play-cost)
      digivolution-requirements (assoc :card/digivolution-requirements
                                       digivolution-requirements)
      level (assoc :card/level level)
      dp (assoc :card/dp dp)
      form (assoc :card/form form)
      attribute (assoc :card/attribute attribute)
      type (assoc :card/type type)
      (seq effect) (assoc :card/effect effect)
      (or (and (= language "ja")
               inherited-effect
               (not (string/starts-with? inherited-effect
                                         "【セキュリティ】")))
          (and (= language "en")
               inherited-effect
               (not (string/starts-with? inherited-effect
                                         "[Security]")))
          (and (= language "ko")
               inherited-effect
               (not (string/starts-with? inherited-effect
                                         "【시큐리티】"))))
      (assoc :card/inherited-effect inherited-effect)
      (or (and (= language "ja")
               security-effect
               (string/starts-with? security-effect
                                    "【セキュリティ】"))
          (and (= language "en")
               security-effect
               (string/starts-with? security-effect
                                    "[Security]"))
          (and (= language "ko")
               security-effect
               (string/starts-with? security-effect
                                    "【시큐리티】")))
      (assoc :card/security-effect security-effect)
      notes (assoc :card/notes
                   (-> (->> notes
                            (map (fn [{:keys [content] :as note}]
                                   (-> (if (string? note)
                                         note
                                         (string/join ". " content))
                                       string/trim
                                       (string/replace "\u25B9" "")
                                       (string/replace #"\uFF3B|\u3010" " [")
                                       (string/replace #"\uFF3D|\u3011" "]"))))
                            (string/join "\n"))
                       (string/replace #"\n+" "\n")
                       string/trim))
      :pack-type (as-> #__ card
                   (if-let [pt (pack-type release card)]
                     (assoc card :card/pack-type pt)
                     card))
      repair-fn repair-fn)))

(defmulti cards-in-release
  (fn [{:release/keys [language]}]
    language))

(defmethod cards-in-release :default
  [{:release/keys [cardlist-uri http-opts] :as release}]
  (let [page (->> (utils/http-get (str cardlist-uri)
                                  http-opts)
                  repair/html-encoding-errors
                  hickory/parse
                  hickory/as-hickory)
        card-detail-selector (select/descendant
                              (select/id "article")
                              (select/class "image_lists")
                              (select/class "popup")
                              (select/class "card_detail"))
        cards-dom-tree (select/select card-detail-selector page)
        additional-pages (->> page
                              (select/select (select/descendant
                                              (select/id "article")
                                              (select/class "paging")
                                              (select/class "page-link")))
                              (map (fn [{{href :href} :attrs}] href))
                              set
                              (sort-by (fn [href]
                                         (some->> href
                                                  (re-find #"[0-9]+$")
                                                  parse-long))))]
    (concat (doall (pmap (partial card release) cards-dom-tree))
            (mapcat (fn [href]
                      (pmap (partial card release)
                            (->> (utils/http-get href
                                                 http-opts)
                                 repair/html-encoding-errors
                                 hickory/parse
                                 hickory/as-hickory
                                 (select/select card-detail-selector))))
                    additional-pages))))

(defmethod cards-in-release "ja"
  [{:release/keys [cardlist-uri http-opts] :as release}]
  (let [page (->> (utils/http-get (str cardlist-uri)
                                  http-opts)
                  repair/html-encoding-errors
                  hickory/parse
                  hickory/as-hickory)
        card-detail-selector (select/descendant
                              (select/id "article")
                              (select/class "image_lists")
                              (select/class "popupCol"))
        cards-dom-tree (select/select card-detail-selector page)
        additional-pages (->> page
                              (select/select (select/descendant
                                              (select/id "article")
                                              (select/class "paging")
                                              (select/class "page-link")))
                              (map (fn [{{href :href} :attrs}] href))
                              set
                              (sort-by (fn [href]
                                         (some->> href
                                                  (re-find #"[0-9]+$")
                                                  parse-long))))
        card (fn [{:release/keys [language cardlist-uri card-image-language]
                  :as release} dom-tree]
               (let [origin (str (.getScheme ^URI cardlist-uri) "://"
                                 (.getHost ^URI cardlist-uri))
                     header (->> (select/select
                                  (select/descendant
                                   (select/and (select/tag "ul")
                                               (select/class "cardTitleList"))
                                   (select/tag "li"))
                                  dom-tree)
                                 (map card-utils/text-content))
                     number (nth header 0)
                     category (nth header 2 "Unknown")
                     alternate-art? (contains? (set header) "パラレル")
                     rarity (string/replace (or (nth header 1) "P")
                                            "Ｕ" "U")
                     dl (->> dom-tree
                             (select/select (select/descendant
                                             (select/tag "dl")
                                             (select/or (select/tag "dt")
                                                        (select/tag "dd"))))
                             (partition-all 2)
                             (reduce (fn [accl [dt dd]]
                                       (assoc accl
                                              (->> dt
                                                   card-utils/text-content
                                                   card-utils/normalize-string)
                                              dd))
                                     {}))
                     colors (->> (get dl "色")
                                 (select/select (select/tag "span"))
                                 (mapcat (fn [el]
                                           (let [color
                                                 (-> (get-in el [:attrs :class] "")
                                                     (string/replace "cardColor_" ""))]
                                             (if (= color "all")
                                               ["red" "blue" "yellow"
                                                "green" "purple" "black"
                                                "white"]
                                               [color]))))
                                 (remove string/blank?)
                                 (map-indexed (fn [i color]
                                                {:color/id (format "color/%s_index%d"
                                                                   number i)
                                                 :color/index i
                                                 :color/color (keyword color)}))
                                 (into []))
                     play-cost (some->> (get dl "登場コスト")
                                        card-utils/text-content
                                        card-utils/normalize-string
                                        parse-long)
                     dp (some->> (get dl "DP")
                                 card-utils/text-content
                                 card-utils/normalize-string
                                 parse-long)
                     form (some->> (get dl "形態")
                                   card-utils/text-content
                                   card-utils/normalize-string)
                     attribute (some->> (get dl "属性")
                                        card-utils/text-content
                                        card-utils/normalize-string)
                     type (some->> (get dl "タイプ")
                                   card-utils/text-content
                                   card-utils/normalize-string)
                     level (some->> (nth header 3 nil)
                                    (re-find #"[0-9]+")
                                    parse-long)
                     image-source (-> (select/select (select/descendant (select/tag "img"))
                                                     dom-tree)
                                      first
                                      (get-in [:attrs :src])
                                      (string/replace "//" "/")
                                      (string/replace #"^\.\." ""))
                     parallel-id (or (some-> (or (re-find #"_P([0-9]+)\."
                                                          image-source)
                                                 (re-find #"_P([0-9]+)\."
                                                          (get-in dom-tree [:attrs :id])))
                                             second
                                             parse-long)
                                     0)
                     card-id (format "card/%s_%s_P%s"
                                     language
                                     number
                                     (str parallel-id))
                     effect (some->> dom-tree
                                     (select/select
                                      (select/descendant
                                       (select/has-child
                                        (select/find-in-text #"^\s*上段テキスト\s*$"))
                                       (select/tag "dd")))
                                     seq
                                     (map (comp card-utils/normalize-string
                                                card-utils/text-content))
                                     (string/join "\n")
                                     card-utils/normalize-string
                                     repair/text-fixes)
                     lower-text (some->> dom-tree
                                         (select/select
                                          (select/descendant
                                           (select/has-child
                                            (select/find-in-text #"^\s*下段テキスト\s*$"))
                                           (select/tag "dd")))
                                         seq
                                         (map (comp card-utils/normalize-string
                                                    card-utils/text-content))
                                         (string/join "\n")
                                         card-utils/normalize-string
                                         repair/text-fixes)
                     digivolution-requirements
                     (->> [(dl "進化条件1")
                           (dl "進化条件2")]
                          (remove nil?)
                          (map-indexed
                           (fn [i c]
                             (let [s (-> c
                                         card-utils/text-content
                                         card-utils/normalize-string)
                                   colors (->> c
                                               (select/select
                                                (select/tag "span"))
                                               (mapcat (fn [el]
                                                         (let [color (-> (get-in el [:attrs :class] "")
                                                                         (string/replace "cardColor_" ""))]
                                                           (if (= color "all")
                                                             ["red" "blue" "yellow"
                                                              "green" "purple" "black"
                                                              "white"]
                                                             [color]))))
                                               (remove nil?)
                                               (map keyword)
                                               set)
                                   category (some->> (string/lower-case s)
                                                     (re-find #"(?i)(.*?)から\d+")
                                                     second)
                                   form (when (and category
                                                   (some (fn [f]
                                                           (string/ends-with? category f))
                                                         #{"並" "超" "極" "神"}))
                                          (case (subs category
                                                      (dec (count category)))
                                            "並" :standard
                                            "超" :super
                                            "極" :ultimate
                                            "神" :god))
                                   level (some->> (string/lower-case s)
                                                  (re-find #"(?i).*lv\.?(\d+).*")
                                                  second
                                                  parse-long)
                                   cost (some->> (string/lower-case s)
                                                 (re-find #"(?i).*(?<!lv\.?)(\d+)")
                                                 second
                                                 parse-long)
                                   category
                                   (cond
                                     (and category
                                          (string/ends-with?
                                           category "テイマー")) :tamer
                                     (or level
                                         form)                   :digimon
                                     :else                       nil)]
                               (cond-> {:digivolve/id (format "digivolve/%s_index%d"
                                                              number i)
                                        :digivolve/category category
                                        :digivolve/index i
                                        :digivolve/cost cost
                                        :digivolve/color colors}
                                 level (assoc :digivolve/level level)
                                 form (assoc :digivolve/form form))))))
                     notes (->> dom-tree
                                (select/select
                                 (select/descendant
                                  (select/has-child
                                   (select/find-in-text #"入手情報"))
                                  (select/tag "dd")))
                                first
                                :content
                                (filter string?)
                                (map (comp string/trim
                                           #(string/replace % "\u25B9" "")
                                           #(string/replace % #"\uFF3B|\u3010" " [")
                                           #(string/replace % #"\uFF3D|\u3011" "]")))
                                (remove string/blank?)
                                (string/join "\n"))
                     repair-fn (-> repair/text-fixes-by-number-by-language
                                   (get-in [number language]))]
                 (cond-> {:card/id card-id
                          :card/release (dissoc release :release/http-opts)
                          :card/language language
                          :card/number number
                          :card/parallel-id parallel-id
                          :card/rarity rarity
                          :card/category category
                          :card/color colors
                          :card/name (->> (select/select
                                           (select/descendant
                                            (select/and (select/tag "div")
                                                        (select/class "cardTitle")))
                                           dom-tree)
                                          first
                                          ((comp card-utils/normalize-string
                                                 string/trim
                                                 (fn [s]
                                                   (-> s
                                                       (string/replace #"\s+" " ")
                                                       (string/replace
                                                        (re-pattern
                                                         (format "%s\\s*(%s)?\\s*"
                                                                 number
                                                                 rarity))
                                                        "")))
                                                 first
                                                 :content)))
                          :card/notes notes
                          :card/image {:image/language card-image-language
                                       :image/source (URI. (str origin image-source))}}
                   play-cost (assoc (if (= category "オプション")
                                      :card/use-cost
                                      :card/play-cost) play-cost)
                   (seq digivolution-requirements) (assoc :card/digivolution-requirements
                                                          digivolution-requirements)
                   level (assoc :card/level level)
                   dp (assoc :card/dp dp)
                   form (assoc :card/form form)
                   attribute (assoc :card/attribute attribute)
                   type (assoc :card/type type)
                   effect (assoc :card/effect effect)
                   (and lower-text
                        (not (string/starts-with? lower-text
                                                  "【セキュリティ】")))
                   (assoc :card/inherited-effect lower-text)
                   (and lower-text
                        (string/starts-with? lower-text
                                             "【セキュリティ】"))
                   (assoc :card/security-effect lower-text)
                   notes (assoc :card/notes notes)
                   :pack-type (as-> #__ card
                                (if-let [pt (pack-type release card)]
                                  (assoc card :card/pack-type pt)
                                  card))
                   repair-fn repair-fn)))]
    (concat (doall (pmap (partial card release) cards-dom-tree))
            (mapcat (fn [href]
                      (pmap (partial card release)
                            (->> (utils/http-get href
                                                 http-opts)
                                 repair/html-encoding-errors
                                 hickory/parse
                                 hickory/as-hickory
                                 (select/select card-detail-selector))))
                    additional-pages))))

(defmethod cards-in-release "zh-Hans"
  [{:release/keys [language cardlist-uri card-image-language] :as release}]
  (let [cards (loop [page 1
                     cards []]
                (let [result (-> (utils/http-get (str cardlist-uri)
                                                 {:query-params {:page page
                                                                 :limit 40}})
                                 json/read-str)
                      cardlist (get-in result ["page" "list"])
                      total-count (get-in result ["page" "totalCount"])]
                  (if (< (+ (count cards) (count cardlist)) total-count)
                    (recur (inc page)
                           (concat cards cardlist))
                    (concat cards cardlist))))]
    (->> cards
         (pmap (fn [{:strs [parallCard belongsType name model form attribute type
                           dp rareDegree entryConsumeValue envolutionConsumeTwo
                           cardLevel effect envolutionEffect safeEffect
                           imageCover getWayStr]}]
                 (when imageCover
                   (let [number (-> model
                                    (string/replace #"_.*" "")
                                    string/trim)
                         parallel-id (if (not= parallCard "0")
                                       0
                                       (or (some-> (re-find #"_([0-9]+)"
                                                            imageCover)
                                                   (nth 1 nil)
                                                   parse-long)
                                           nil))
                         card-id (format "card/%s_%s_P%s"
                                         language
                                         number
                                         (str parallel-id))
                         level (some->> cardLevel
                                        card-utils/normalize-string
                                        (re-find #"[0-9]+")
                                        parse-long)
                         attribute (some-> attribute card-utils/normalize-string)
                         type (some-> type card-utils/normalize-string)
                         form (some-> form card-utils/normalize-string)
                         notes (some-> getWayStr card-utils/normalize-string)
                         play-cost (or (some-> entryConsumeValue
                                               card-utils/normalize-string
                                               parse-long)
                                       (some-> envolutionConsumeTwo
                                               card-utils/normalize-string
                                               parse-long))
                         dp (some-> dp card-utils/normalize-string parse-long)
                         effect (some-> effect
                                        card-utils/normalize-string
                                        repair/text-fixes
                                        (string/replace "enter" "\n"))
                         inherited-effect (some-> envolutionEffect
                                                  card-utils/normalize-string
                                                  repair/text-fixes
                                                  (string/replace "enter" "\n"))
                         security-effect (some-> safeEffect
                                                 card-utils/normalize-string
                                                 repair/text-fixes
                                                 (string/replace "enter" "\n"))
                         repair-fn (-> repair/text-fixes-by-number-by-language
                                       (get-in [number language]))]
                     (cond-> {:card/id card-id
                              :card/release (dissoc release
                                                    :release/card-image-language)
                              :card/language language
                              :card/number number
                              :card/parallel-id parallel-id
                              :card/rarity (last (re-find #"（(.*)）" rareDegree))
                              :card/category belongsType
                              :card/name (card-utils/normalize-string name)
                              :card/image {:image/language card-image-language
                                           :image/source (URI. imageCover)}}
                       play-cost (assoc (if (= belongsType "选项")
                                          :card/use-cost
                                          :card/play-cost) play-cost)
                       level (assoc :card/level level)
                       dp (assoc :card/dp dp)
                       form (assoc :card/form form)
                       notes (assoc :card/notes notes)
                       attribute (assoc :card/attribute
                                        (card-utils/normalize-string attribute))
                       type (assoc :card/type type)
                       (seq effect) (assoc :card/effect effect)
                       (seq inherited-effect)
                       (assoc :card/inherited-effect inherited-effect)
                       (seq security-effect) (assoc :card/security-effect
                                                    security-effect)
                       :pack-type (as-> #__ card
                                    (if-let [pt (pack-type release card)]
                                      (assoc card :card/pack-type pt)
                                      card))
                       repair-fn (as-> #__ card
                                   (try (repair-fn card)
                                        (catch Exception e
                                          (logging/error card e)
                                          (throw e)))))))))
         (remove nil?)
         doall)))
