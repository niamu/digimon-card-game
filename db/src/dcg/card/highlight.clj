(ns dcg.card.highlight
  (:require
   [clojure.string :as string]
   [dcg.card.utils :as utils]
   [taoensso.timbre :as logging])
  (:import
   [java.util Date]))

(defn- text->highlight-type
  [text]
  (when text
    (let [b (subs text 0 1)]
      (case b
        "\u201C" :mention
        "「" :mention
        "【" :timing
        "["  :phase
        "［" :phase
        "<"  :keyword-effect
        "≪"  :keyword-effect
        "〈" :keyword-effect
        "《" :keyword-effect
        "＜" :keyword-effect
        nil))))

(def ^:private card-highlights-re
  (let [re-escape-map {\[ "\\["
                       \] "\\]"
                       \( "\\("
                       \) "\\)"}
        squares [["\u300C" "\u300D"]
                 ["\u3010" "\u3011"]
                 ["[" "]"]
                 ["\uFF3B" "\uFF3D"]
                 ["\u201C" "\u201D"]]
        disallow-tokens (->> ["（[^）]+）?のトークン"
                              "(（[^）]+）)?以外の"
                              "以外の名称に"
                              "的数码宝贝不作为对象"
                              "的数码宝贝卡牌不作为对象"
                              "\\sToken"
                              "（[^）]+）的替代卡"
                              "（[^）]+）以外"
                              "\\s*\\(.*?\\)\\s*.?\\s*토큰"
                              ;; gains
                              "の効果を"
                              "を得る"
                              "의 효과를 얻는다"
                              ;; other than
                              "以外的"]
                             (string/join "|"))
        squares-format (map (fn [[open close]]
                              (format (str ""
                                           ;; within name
                                           "(名称に|の名称は|"
                                           "于名称中包含)?"
                                           ;; within characteristic
                                           "(か?特徴に|特徴.?)?"
                                           ;; or previous
                                           "(カードか|か|／|/|或|(?<!w)/)?"
                                           ;; also treat as
                                           "(このカード/デジモンの名称は|"
                                           "此卡属于名称中包含|"
                                           "此卡牌/数码宝贝的名)?"
                                           ;; other than
                                           "(?<!other\\sthan\\s)"
                                           "(?<!non\\-)"
                                           "(?<!without\\s)"
                                           ;; this digimon
                                           "%s(?!"
                                           "【|\\[|"
                                           "このデジモン|"
                                           "当?此数码宝|"
                                           "이 디지몬은|"
                                           "이 디지몬이 )"
                                           ;; brackets
                                           "([^%s，,]+)%s"
                                           ;; or next
                                           "(か|／|/|或|と|(?<!w)/)?"
                                           ;; within text
                                           "(の記述がある)?"
                                           ;; token
                                           "(?!（[^）]+）のトークン)"
                                           "(?!%s)")
                                      (string/escape open re-escape-map)
                                      (string/escape close re-escape-map)
                                      (string/escape close re-escape-map)
                                      disallow-tokens))
                            squares)
        angles  [["<" ">"]
                 ["\u226A" "\u226B"]
                 ["\u3008" "\u3009"]
                 ["\u300A" "\u300B"]
                 ["\uFF1C" "\uFF1E"]]
        angles-format (map (fn [[open close]]
                             (format "%s([^%s]+)%s"
                                     (string/escape open re-escape-map)
                                     (string/escape close re-escape-map)
                                     (string/escape close re-escape-map)))
                           angles)]
    (->> (concat ["\\([^\\)]+\\)"
                  "（[^）]+）"] ;; text within parens
                 squares-format
                 angles-format)
         (string/join "|")
         re-pattern)))

(defn- translation-map
  [by-language translations]
  (let [en (get by-language "en")
        ja (get by-language "ja")
        zh-Hans (get by-language "zh-Hans")
        ko (get by-language "ko")]
    (cond-> {}
      (and en (not (get-in translations ["en" en])))
      (assoc-in ["en" en] ja)
      (and zh-Hans (not (get-in translations ["zh-Hans" zh-Hans])))
      (assoc-in ["zh-Hans" zh-Hans] ja)
      (and ko (not (get-in translations ["ko" ko])))
      (assoc-in ["ko" ko] ja))))

(defn- by-field-and-text
  [highlights]
  (reduce (fn [accl {:highlight/keys [text field] :as highlight}]
            (let [text (subs text 1 (dec (count text)))
                  lookup (get-in accl [field text])
                  h (select-keys highlight
                                 [:highlight/type
                                  :highlight/mention
                                  :highlight/copy-to-next?])]
              (if (or lookup
                      (and (= (:highlight/type h) :mention)
                           (not (:highlight/mention h))))
                accl
                (assoc-in accl [field text] h))))
          {}
          highlights))

(defn- by-field-and-text-and-index
  [highlights]
  (reduce (fn [accl {:highlight/keys [index text field] :as highlight}]
            (let [text (subs text 1 (dec (count text)))
                  lookup (get-in accl [field [text index]])
                  h (select-keys highlight
                                 [:highlight/type
                                  :highlight/mention
                                  :highlight/copy-to-next?])]
              (if (or lookup
                      (and (= (:highlight/type h) :mention)
                           (not (:highlight/mention h))))
                accl
                (assoc-in accl [field [text index]] h))))
          {}
          highlights))

(defn- by-field-and-index
  [highlights]
  (reduce (fn [accl {:highlight/keys [index field] :as highlight}]
            (let [lookup (get-in accl [field index])
                  h (select-keys highlight
                                 [:highlight/type
                                  :highlight/mention
                                  :highlight/copy-to-next?])]
              (if (or lookup
                      (and (= (:highlight/type h) :mention)
                           (not (:highlight/mention h))))
                accl
                (assoc-in accl [field index] h))))
          {}
          highlights))

(def ^:private text-attributes
  #{"データ種" ;; Data
    "数据种" ;; Data
    "Virus"
    "三銃士" ;; Three Muskateers
    "X抗体" ;; X-Antibody
    "X-Antibody"})

(def ^:private effect-text-fixes
  "https://world.digimoncard.com/rule/effect_text/effects_reference.php"
  {"en" {"DeathX" "デクス"
         "Sea Animal" "水棲"
         "Armor Form" "アーマー体"
         "saur" "竜"
         "Ceratopsian" "竜"
         "Plant" "植物型"
         "Vegetation" "植物型"
         "Bird" "鳥"
         "Avian" "鳥"
         "Animal" "獣"
         "Beast" "獣"
         "D-Reaper" "デ・リーパー"
         "Fairy" "妖精型"
         "Sovereign" "獣"
         "Cherub" "天使型"
         "Throne" "天使型"
         "Authority" "天使型"
         "Seraph" "天使型"
         "Virtue" "天使型"
         "Royal Knights" "ロイヤルナイツ"
         "Ryouma Mogami" "最上リョウマ"
         "Dragon Mode" "ドラゴンモード"
         "Belphemon: Rage Mode" "ベルフェモン:レイジモード"}
   "zh-Hans" {"天使型" "天使型"
              "大天使型" "天使型"
              "堕天使型" "天使型"
              "修女兽" "シスタモン"
              "龙形态" "ドラゴンモード"
              "龙兽" "ドラモン"
              "正义兽" "ジャスティモン"
              "正义兽:加速武装" "ジャスティモン:アクセルアーム"
              "正义兽:临界武装" "ジャスティモン:クリティカルアーム"
              "奥米加兽" "オメガモン"
              "究极体" "究極体"
              "装甲体" "アーマー体"
              "帝皇龙甲兽:龙形态" "インペリアルドラモン:ドラゴンモード"
              "贝尔菲兽:愤怒形态" "ベルフェモン:レイジモード"}})

(defn- highlights-in-text
  [text]
  (->> (-> text
           (string/replace #"※カードナンバー.*" "")  ;; Ignore "*card number"
           (string/replace #"※此卡不.*?的数码宝贝" "")) ;; This card does not...
       (re-seq card-highlights-re)
       (remove (fn [[match & contents]]
                 (let [re-escape-map {\- "\\-"
                                      \+ "\\+"
                                      \[ "\\["
                                      \] "\\]"
                                      \( "\\("
                                      \) "\\)"}
                       content (re-find
                                (->> (map (fn [[open close]]
                                            (format "%s[^%s]+%s"
                                                    (string/escape
                                                     open re-escape-map)
                                                    (string/escape
                                                     close re-escape-map)
                                                    (string/escape
                                                     close re-escape-map)))
                                          [["\u300C" "\u300D"]
                                           ["\u3010" "\u3011"]
                                           ["[" "]"]
                                           ["\uFF3B" "\uFF3D"]
                                           ["\u201C" "\u201D"]
                                           ["<" ">"]
                                           ["\u226A" "\u226B"]
                                           ["\u3008" "\u3009"]
                                           ["\u300A" "\u300B"]
                                           ["\uFF1C" "\uFF1E"]])
                                     (string/join "|")
                                     re-pattern)
                                match)
                       highlight-type (text->highlight-type content)]
                   (or (every? nil? contents)
                       (nil? highlight-type)))))
       (reduce (fn [accl [match & contents]]
                 (let [[in-name?
                        in-characteristic?
                        _
                        also-treat-as?
                        brackets
                        or-next?
                        in-text? _] (->> contents
                                         (partition-all 8)
                                         (remove #(every? nil? %))
                                         first)
                       index (count accl)
                       prev (peek accl)
                       re-escape-map {\- "\\-"
                                      \+ "\\+"
                                      \[ "\\["
                                      \] "\\]"
                                      \( "\\("
                                      \) "\\)"}
                       text (re-find
                             (->> (map (fn [[open close]]
                                         (format "%s[^%s]+%s"
                                                 (string/escape
                                                  open re-escape-map)
                                                 (string/escape
                                                  close re-escape-map)
                                                 (string/escape
                                                  close re-escape-map)))
                                       [["\u300C" "\u300D"]
                                        ["\u3010" "\u3011"]
                                        ["[" "]"]
                                        ["\uFF3B" "\uFF3D"]
                                        ["\u201C" "\u201D"]
                                        ["<" ">"]
                                        ["\u226A" "\u226B"]
                                        ["\u3008" "\u3009"]
                                        ["\u300A" "\u300B"]
                                        ["\uFF1C" "\uFF1E"]])
                                  (string/join "|")
                                  re-pattern)
                             match)
                       content (subs text 1 (dec (count text)))
                       in-name? (or in-name?
                                    (and also-treat-as?
                                         (string/includes? also-treat-as?
                                                           "于名称中包含")))
                       also-treat-as? (boolean
                                       (or (boolean also-treat-as?)
                                           (and in-name?
                                                (string/includes? in-name?
                                                                  "此卡属"))
                                           (and in-name?
                                                (string/ends-with? in-name?
                                                                   ":"))
                                           (and in-characteristic?
                                                (string/ends-with?
                                                 in-characteristic? ":"))))
                       in-characteristic? (or (boolean in-characteristic?)
                                              (contains? text-attributes
                                                         content))
                       or-next? (boolean or-next?)
                       exact? (or (and also-treat-as?
                                       (string/includes? also-treat-as?
                                                         "于名称中包含"))
                                  (and (not in-characteristic?)
                                       (not in-name?)
                                       (not in-text?)))
                       fields (cond
                                in-name?                #{:card/name}
                                in-characteristic?      #{:card/form
                                                          :card/attribute
                                                          :card/digimon-type}
                                in-text?                #{:card/effect
                                                          :card/security-effect
                                                          :card/inherited-effect}
                                (and (not in-characteristic?)
                                     also-treat-as?)    #{:card/number
                                                          :card/name}
                                :else                   #{:card/number
                                                          :card/name})
                       highlight-type (text->highlight-type text)
                       highlight-type (cond
                                        (or (string/includes? text "デジクロス")
                                            (string/includes? text "DigiXros")
                                            (string/includes? text "数码合体"))
                                        :digixros
                                        (or (string/includes? text "ジョグレス")
                                            (string/includes? text "DNA Digi")
                                            (string/includes? text "合步"))
                                        :dna-digivolve
                                        :else highlight-type)]
                   (conj accl
                         (cond-> {:highlight/index index
                                  :highlight/text text
                                  :highlight/type highlight-type}
                           (= highlight-type :mention)
                           (assoc :highlight/mention
                                  (if (and (get-in prev
                                                   [:highlight/mention
                                                    :mention/copy-to-next?])
                                           (not in-characteristic?))
                                    (-> (get-in prev [:highlight/mention])
                                        (assoc :mention/copy-to-next? or-next?))
                                    (cond-> {:mention/exact? exact?
                                             :mention/fields fields
                                             :mention/copy-to-next? or-next?}
                                      also-treat-as?
                                      (assoc :mention/aka?
                                             also-treat-as?))))))))
               [])))

(defn- card-highlights
  [{:card/keys [language number] :as card}]
  (mapcat (fn [k]
            (when-let [text (get card k)]
              (map (fn [highlight]
                     (cond-> (assoc highlight
                                    :highlight/id
                                    (format "highlight/%s_%s_%s%d"
                                            language
                                            number
                                            (name k)
                                            (get highlight :highlight/index))
                                    :highlight/field k
                                    :highlight/card-number number
                                    :highlight/language language)
                       (and (= language "en")
                            (not= :keyword-effect (:highlight/type highlight))
                            (not= :digixros (:highlight/type highlight)))
                       (dissoc :highlight/type)))
                   (highlights-in-text text))))
          [:card/effect
           :card/security-effect
           :card/inherited-effect]))

(defn- highlights
  [card-groups]
  (loop [card-group-index 0
         translations (reduce (fn [accl card-group]
                                (let [name-by-language
                                      (->> card-group
                                           (mapcat (juxt :card/language
                                                         :card/name))
                                           (apply hash-map))
                                      en (get name-by-language "en")
                                      ja (get name-by-language "ja")
                                      zh-Hans (get name-by-language "zh-Hans")
                                      ko (get name-by-language "ko")]
                                  (cond-> accl
                                    (and en
                                         (not (get-in accl ["en" en])))
                                    (assoc-in ["en" en] ja)
                                    (and zh-Hans
                                         (not (get-in accl
                                                      ["zh-Hans" zh-Hans])))
                                    (assoc-in ["zh-Hans" zh-Hans] ja)
                                    (and ko
                                         (not (get-in accl ["ko" ko])))
                                    (assoc-in ["ko" ko] ja))))
                              effect-text-fixes
                              card-groups)
         all-highlights []]
    (if-let [card-group (some->> (nth card-groups card-group-index nil)
                                 (sort-by (comp {"ko" 3
                                                 "zh-Hans" 2
                                                 "en" 1
                                                 "ja" 0}
                                                :card/language)))]
      (let [highlights (mapcat card-highlights card-group)
            field-and-text-and-index (by-field-and-text-and-index highlights)
            field-and-text (by-field-and-text highlights)
            field-and-index (by-field-and-index highlights)
            highlights (map (fn [{:highlight/keys [language index text field]
                                 :as highlight}]
                              (let [text (subs text 1 (dec (count text)))
                                    ja-text (get-in translations
                                                    [language text])]
                                (cond
                                  (or (= (:highlight/type highlight)
                                         :keyword-effect)
                                      (= (:highlight/type highlight)
                                         :digixros)) highlight
                                  (and ja-text
                                       (not= language "ja")
                                       (get-in field-and-text-and-index
                                               [field [ja-text index]]))
                                  (merge highlight
                                         (get-in field-and-text-and-index
                                                 [field [ja-text index]]))
                                  (and ja-text
                                       (not= language "ja")
                                       (get-in field-and-text
                                               [field ja-text]))
                                  (merge highlight
                                         (get-in field-and-text
                                                 [field ja-text]))
                                  :else
                                  (merge highlight
                                         (get-in field-and-index
                                                 [field index])))))
                            highlights)
            highlights-translations
            (->> highlights
                 (remove (fn [{:highlight/keys [language text]}]
                           (get-in translations
                                   [language
                                    (subs text 1 (dec (count text)))])))
                 (reduce (fn [accl {:highlight/keys [index language type field]
                                   :as highlight}]
                           (update-in accl [[index type field]]
                                      conj highlight))
                         {})
                 (reduce (fn [accl [_ highlights]]
                           (if (= (count highlights) 1)
                             accl
                             (merge accl
                                    (translation-map
                                     (reduce (fn [result {:highlight/keys
                                                         [language text]}]
                                               (assoc result
                                                      language
                                                      (->> (dec (count text))
                                                           (subs text 1))))
                                             {}
                                             highlights)
                                     translations))))
                         {}))]
        (recur (inc card-group-index)
               (merge-with merge translations highlights-translations)
               (concat all-highlights highlights)))
      {:translations translations
       :card-highlights all-highlights})))

(defn- card-numbers-mentioned
  [cards treats-lookup {:highlight/keys [language text]
                        {:mention/keys [exact? fields]} :highlight/mention
                        :as highlight}]
  (let [text (subs text 1 (dec (count text)))]
    (->> cards
         (filter (fn [{:card/keys [number] :as card}]
                   (and (= language (:card/language card))
                        (or (cond->> (vals (select-keys card fields))
                              exact?
                              (some (fn [s]
                                      (or (= (string/lower-case s)
                                             (string/lower-case text))
                                          (= (string/lower-case s)
                                             (-> text
                                                 string/lower-case
                                                 (string/replace ":" "")))
                                          (and (contains? fields :card/number)
                                               (= (re-find utils/card-number-re
                                                           text)
                                                  s)))))
                              (not exact?)
                              (some (fn [s]
                                      (string/includes? s text))))
                            (when-let [treat (get treats-lookup
                                                  [language number text])]
                              (contains? fields (:treat/field treat)))))))
         (map :card/number)
         set)))

(defn all
  [cards]
  (let [card-groups
        (->> cards
             (filter (comp zero? :card/parallel-id))
             (sort-by (juxt :card/number
                            :card/parallel-id
                            (juxt (comp (fnil inst-ms (Date. Long/MAX_VALUE))
                                        :release/date
                                        first
                                        :card/releases)
                                  :card/number)))
             (partition-by :card/number))
        {:keys [card-highlights translations]} (highlights card-groups)
        treats-lookup
        (reduce (fn [accl {:highlight/keys [id card-number language text mention]
                          :as highlight}]
                  (if (:mention/aka? mention)
                    (let [id (string/replace id "highlight/" "treat/")
                          field (if (contains? (:mention/fields mention)
                                               :card/name)
                                  :card/name
                                  :card/attribute)
                          text (subs text 1 (dec (count text)))]
                      (assoc-in accl
                                [[language card-number text]]
                                {:treat/id id
                                 :treat/card-number card-number
                                 :treat/language language
                                 :treat/as text
                                 :treat/field field}))
                    accl))
                {}
                card-highlights)
        ja-mentioned-cards-lookup
        (reduce (fn [accl {:highlight/keys [id card-number language text mention]
                          :as highlight}]
                  (if (and mention
                           (not (:mention/aka? mention))
                           (= language "ja"))
                    (let [text (subs text 1 (dec (count text)))]
                      (assoc-in accl
                                [card-number text mention]
                                (card-numbers-mentioned cards
                                                        treats-lookup
                                                        highlight)))
                    accl))
                {}
                card-highlights)
        mentions
        (reduce (fn [accl {:highlight/keys [id card-number language text mention]
                          :as highlight}]
                  (if (and mention (not (:mention/aka? mention)))
                    (let [text (subs text 1 (dec (count text)))
                          ja-text (get-in translations [language text])
                          card-numbers (or (get-in ja-mentioned-cards-lookup
                                                   [card-number
                                                    text
                                                    mention])
                                           (get-in ja-mentioned-cards-lookup
                                                   [card-number
                                                    ja-text
                                                    mention]))
                          mentioned-cards
                          (->> cards
                               (filter (fn [{:card/keys [number image] :as card}]
                                         (and (= language
                                                 (:card/language card)
                                                 (:image/language image))
                                              (contains? card-numbers
                                                         number))))
                               (map (fn [{:card/keys [id]}]
                                      {:card/id id})))]
                      (conj accl
                            {:mention/id (string/replace id
                                                         "highlight/"
                                                         "mention/")
                             :mention/card-number card-number
                             :mention/language language
                             :mention/text text
                             :mention/cards mentioned-cards}))
                    accl))
                []
                card-highlights)]
    {:mentions (reduce (fn [accl {:mention/keys [language card-number]
                                 :as mention}]
                         (update-in accl [card-number language]
                                    conj
                                    (dissoc mention
                                            :mention/card-number
                                            :mention/language)))
                       {}
                       mentions)
     :treats (reduce (fn [accl {:treat/keys [language card-number]
                               :as treat}]
                       (update-in accl [card-number language]
                                  conj
                                  (dissoc treat
                                          :treat/card-number
                                          :treat/language)))
                     {}
                     (vals treats-lookup))
     :highlights (->> card-highlights
                      (map (fn [{:highlight/keys [id mention] :as highlight}]
                             (cond-> highlight
                               (and mention (not (:mention/aka? mention)))
                               (assoc :highlight/mention
                                      {:mention/id (string/replace id
                                                                   "highlight/"
                                                                   "mention/")})
                               (and mention (:mention/aka? mention))
                               (-> (assoc :highlight/treat
                                          {:treat/id
                                           (string/replace id
                                                           "highlight/"
                                                           "treat/")})
                                   (assoc :highlight/type :treat)
                                   (dissoc :highlight/mention)))))
                      (reduce (fn [accl {:highlight/keys [language card-number]
                                        :as highlight}]
                                (update-in accl [card-number language]
                                           conj
                                           (dissoc highlight
                                                   :highlight/card-number
                                                   :highlight/language)))
                              {}))}))
