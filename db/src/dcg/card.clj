(ns dcg.card
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.card.cv :as cv]
   [dcg.card.repair :as repair]
   [dcg.card.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.net URI]
   [java.util Date]))

(defn- download-image!
  [{:image/keys [id language source] :as image}]
  (let [number (-> id
                   (string/replace #"image/(.*?)_" "")
                   (string/replace #"_P([0-9]+)" ""))
        parallel-id (last (re-find #"_P([0-9]+)" id))
        filename (format "resources/images/cards/%s/%s.png"
                         language
                         (cond-> number
                           (not= parallel-id "0") (str "_P" parallel-id)))]
    (when-not (.exists (io/file filename))
      (let [image-bytes (-> (str source)
                            utils/as-bytes
                            utils/trim-transparency!)]
        (.mkdirs (io/file (.getParent (io/file filename))))
        (with-open [in (io/input-stream image-bytes)
                    out (io/output-stream filename)]
          (io/copy in out))
        (logging/info (format "Downloaded image: [%s] %s" id (str source)))))
    image))

(defn- ^:deprecated download-icon!
  "Deprecated since the shutdown of mypage.digimoncard.com"
  [{:image/keys [id language] :as image}]
  (let [number (-> id
                   (string/replace #"image/(.*?)_" "")
                   (string/replace #"_P([0-9]+)" ""))
        parallel-id (last (re-find #"_P([0-9]+)" id))
        filename (format "resources/images/icons/%s.png"
                         (cond-> number
                           (not= parallel-id "0") (str "_P" parallel-id)))
        image-uri (format "https://mypage.digimoncard.com/images/card/icon/%s.png"
                          (cond-> number
                            (not= parallel-id "0") (str "_P" parallel-id)))]
    (when (and (= parallel-id "0")
               (not (.exists (io/file filename))))
      (when-let [image-bytes (try (-> image-uri
                                      utils/as-bytes
                                      utils/trim-transparency!)
                                  (catch Exception _ nil))]
        (.mkdirs (io/file (.getParent (io/file filename))))
        (with-open [in (io/input-stream image-bytes)
                    out (io/output-stream filename)]
          (io/copy in out))
        (logging/info (format "Downloaded icon: %s" (str image-uri)))))
    image))

(defn post-processing-per-origin
  [releases cards]
  (let [releases-by-set
        (reduce (fn [accl {:release/keys [name] :as release}]
                  (if-let [set-id (some-> (re-find (utils/within-brackets-re
                                                    (get utils/text-punctuation
                                                         :square-brackets))
                                                   name)
                                          rest
                                          (as-> x (remove nil? x))
                                          first
                                          (string/replace "-" "")
                                          (string/replace #"0([0-9]+)" "$1"))]
                    (assoc accl set-id release)
                    accl))
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
                        (fn [{:card/keys [parallel-id]}]
                          (if (nil? parallel-id)
                            Long/MAX_VALUE
                            parallel-id))
                        (comp str
                              :image/source
                              :card/image)))
         (reduce (fn [accl {:card/keys [number language parallel-id image
                                       notes release] :as card}]
                   (let [prev-card (peek accl)
                         release (dissoc release :release/card-image-language)
                         releases-in-notes
                         (some->> notes
                                  (re-seq (utils/within-brackets-re
                                           (get utils/text-punctuation
                                                :square-brackets)))
                                  (mapcat rest)
                                  (remove nil?)
                                  (map (fn [s]
                                         (-> s
                                             (string/replace "-" "")
                                             (string/replace #"0([0-9]+)"
                                                             "$1"))))
                                  (filter (fn [r] (contains? release-set-ids r)))
                                  (map releases-by-set)
                                  (sort-by (comp (fnil inst-ms
                                                       (Date. Long/MAX_VALUE))
                                                 :release/date))
                                  (map #(dissoc % :release/card-image-language)))
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
                                       (not (empty? existing-card-numbers))
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
                                            (assoc image
                                                   :image/id
                                                   (string/replace
                                                    card-id
                                                    #"^card/"
                                                    "image/")
                                                   :image/path
                                                   (format
                                                    (str "resources/images/"
                                                         "cards/%s/%s.png")
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

(defn image-processing
  [cards]
  (let [cards (pmap (fn [{:card/keys [language image parallel-id id] :as card}]
                      (cond-> (update card
                                      :card/image (comp #_download-icon!
                                                        download-image!))
                        (and (= language "ja")
                             (zero? parallel-id)) cv/digivolve-conditions
                        (= language
                           (:image/language image)) cv/block-marker))
                    cards)]
    (logging/info "Adding cards to FLANN DB...")
    (doseq [card cards]
      (cv/add! card))
    (logging/info "Training FLANN DB...")
    (cv/train!)
    (logging/info "FLANN DB Trained.")
    cards))

(defn- pack-type
  [{:release/keys [genre] :as release} {:card/keys [notes number] :as card}]
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
                    (map utils/text-content))
        number (-> (nth header 0)
                   ;; ko cards sometimes add a "P" suffix to the card number
                   (string/replace #"P$" ""))
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
                        utils/normalize-string
                        utils/text-content))
             (apply hash-map))
        info-bottom
        (->> (dl "cardinfo_bottom")
             (drop-last 2)
             (map (comp repair/text-fixes
                        utils/normalize-string
                        utils/text-content))
             (apply hash-map))
        notes (->> (select/select (select/descendant
                                   (select/class "cardinfo_bottom")
                                   (select/follow-adjacent
                                    (select/find-in-text #"Notes|入手情報")
                                    (select/tag "dd")))
                                  dom-tree)
                   first
                   :content)
        colors (->> (get-in dom-tree [:attrs :class])
                    (re-seq (re-pattern (->> [:red
                                              :blue
                                              :yellow
                                              :green
                                              :black
                                              :purple
                                              :white]
                                             (map name)
                                             (string/join "|"))))
                    (mapv keyword))
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
        digivolve-conditions
        (->> (->> (dl "cardinfo_top_body")
                  (map (comp utils/normalize-string string/trim first :content))
                  (partition-all 2)
                  (filter (fn [[k v]]
                            (and (or (string/includes? k "Digivolve")
                                     (string/includes? k "進化コスト")
                                     (string/includes? k "진화 비용"))
                                 v)))
                  (map second))
             (remove (fn [s] (or (empty? s) (= s "-"))))
             (remove nil?)
             (reduce (fn [accl c]
                       (if-let [c c]
                         (let [i (count accl)
                               from (or (some->> (string/lower-case c)
                                                 (re-find #".*lv\.?(\d).*")
                                                 second
                                                 parse-long)
                                        (get (first accl) :digivolve/level))
                               cost (or (some->> (string/lower-case c)
                                                 (re-find #".*(?<!lv\.?)(\d)")
                                                 second
                                                 parse-long)
                                        (get (first accl) :digivolve/cost))]
                           (conj accl
                                 {:digivolve/id (format "digivolve/%s_index%d"
                                                        number i)
                                  :digivolve/index i
                                  :digivolve/cost cost
                                  :digivolve/level from
                                  :digivolve/color [(nth colors i
                                                         (first colors))]}))
                         accl))
                     []))
        digivolve-conditions (->> (if (empty? (remove nil?
                                                      digivolve-conditions))
                                    nil
                                    digivolve-conditions))
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
        digimon-type (or (get info-top "Type")
                         (get info-top "タイプ")
                         (get info-top "유형"))
        [attribute digimon-type] (if (and (contains? #{"Variable"
                                                       "Free"
                                                       "Data"
                                                       "Unidentified"
                                                       "Unknown"
                                                       "Vaccine"
                                                       "Virus"} digimon-type)
                                          attribute)
                                   [digimon-type attribute]
                                   [attribute digimon-type])
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
        limitation
        (and (string? effect)
             (or (some->> effect
                          (re-find #"はデッキに([0-9]+)枚まで入れられる")
                          last
                          parse-long)
                 (some->> effect
                          (re-find #"include up to ([0-9]+) copies of")
                          last
                          parse-long)))
        repair-fn (get-in repair/text-fixes-by-number-by-language
                          [number language])]
    (cond-> {:card/id card-id
             :card/release release
             :card/language language
             :card/number number
             :card/parallel-id parallel-id
             :card/rarity rarity
             :card/type (string/replace (nth header 2) "DIgimon" "Digimon")
             :card/color colors
             :card/name (->> (select/select
                              (select/descendant
                               (select/and (select/tag "div")
                                           (select/class "card_name")))
                              dom-tree)
                             first
                             ((comp utils/normalize-string
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
      play-cost (assoc :card/play-cost play-cost)
      digivolve-conditions (assoc :card/digivolve-conditions
                                  digivolve-conditions)
      level (assoc :card/level level)
      dp (assoc :card/dp dp)
      form (assoc :card/form form)
      attribute (assoc :card/attribute attribute)
      digimon-type (assoc :card/digimon-type digimon-type)
      (not (empty? effect)) (assoc :card/effect effect)
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
      limitation (assoc :card/limitation
                        {:limitation/id (format "limitation/%s_%s"
                                                language
                                                number)
                         :limitation/type :card
                         :limitation/allowance limitation})
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
                       (string/replace #"\n+" "\n")))
      :pack-type (as-> #__ card
                   (if-let [pt (pack-type release card)]
                     (assoc card :card/pack-type pt)
                     card))
      repair-fn repair-fn)))

(defmulti cards-in-release
  (fn [{:release/keys [language]}]
    language))

(defmethod cards-in-release :default
  [{:release/keys [language cardlist-uri] :as release}]
  (let [page (->> (utils/http-get (str cardlist-uri))
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
    (concat (pmap (partial card release) cards-dom-tree)
            (mapcat (fn [href]
                      (pmap (partial card release)
                            (->> (utils/http-get href)
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
    (pmap (fn [{:strs [parallCard belongsType name model form attribute type
                       dp rareDegree entryConsumeValue envolutionConsumeTwo
                       cardLevel effect envolutionEffect safeEffect
                       imageCover cardGroup]}]
            (let [number (-> model
                             (string/replace #"_.*" "")
                             string/trim)
                  parallel-id (when (not= parallCard "0")
                                0)
                  card-id (format "card/%s_%s_P%s"
                                  language
                                  number
                                  (str parallel-id))
                  level (some->> cardLevel
                                 utils/normalize-string
                                 (re-find #"[0-9]+")
                                 parse-long)
                  attribute (some-> attribute utils/normalize-string)
                  digimon-type (some-> type utils/normalize-string)
                  form (some-> form utils/normalize-string)
                  play-cost (or (some-> entryConsumeValue
                                        utils/normalize-string
                                        parse-long)
                                (some-> envolutionConsumeTwo
                                        utils/normalize-string
                                        parse-long))
                  dp (some-> dp utils/normalize-string parse-long)
                  effect (some-> effect
                                 utils/normalize-string
                                 (string/replace "enter" "\n"))
                  inherited-effect (some-> envolutionEffect
                                           utils/normalize-string
                                           (string/replace "enter" "\n"))
                  security-effect (some-> safeEffect
                                          utils/normalize-string
                                          (string/replace "enter" "\n"))
                  limitation
                  (and effect
                       (some->> effect
                                (re-find #"中可以放入最多([0-9]+)张卡牌编号与此卡")
                                last
                                parse-long))
                  repair-fn (get-in repair/text-fixes-by-number-by-language
                                    [number language])]
              (cond-> {:card/id card-id
                       :card/release (dissoc release
                                             :release/card-image-language)
                       :card/language language
                       :card/number number
                       :card/parallel-id parallel-id
                       :card/rarity (last (re-find #"（(.*)）" rareDegree))
                       :card/type belongsType
                       :card/name name
                       :card/image {:image/language card-image-language
                                    :image/source (URI. imageCover)}}
                play-cost (assoc :card/play-cost play-cost)
                level (assoc :card/level level)
                dp (assoc :card/dp dp)
                form (assoc :card/form form)
                attribute (assoc :card/attribute
                                 (utils/normalize-string attribute))
                digimon-type (assoc :card/digimon-type digimon-type)
                (not (empty? effect)) (assoc :card/effect effect)
                (not (empty? inherited-effect))
                (assoc :card/inherited-effect inherited-effect)
                (not (empty? security-effect)) (assoc :card/security-effect
                                                      security-effect)
                limitation (assoc :card/limitation
                                  {:limitation/id (format "limitation/%s_%s"
                                                          language
                                                          number)
                                   :limitation/type :card
                                   :limitation/allowance limitation})
                :pack-type (as-> #__ card
                             (if-let [pt (pack-type release card)]
                               (assoc card :card/pack-type pt)
                               card))
                repair-fn repair-fn)))
          cards)))
