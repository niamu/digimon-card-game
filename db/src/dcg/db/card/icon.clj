(ns dcg.db.card.icon
  (:require
   [clojure.string :as string])
  (:import
   [java.util Date]))

(defn- text->icon-type
  [text language]
  (when text
    (cond
      (or (string/includes? text "デジクロス")
          (string/includes? text "DigiXros")
          (string/includes? text "数码合体")
          (string/includes? text "디지크로스")) :digixros
      (or (re-matches #".リンク." text)
          (re-matches #".Link." text)
          (re-matches #".链接." text)
          ;; TODO: KO Link
          ) :link
      (or (re-matches #".アセンブリ\-[0-9]." text)
          (re-matches #".Assembly\s\-[0-9]." text)
          (re-matches #".组装\-[0-9]." text)
          ;; TODO: KO Assembly
          ) :assembly
      (or (re-matches #".ルール." text)
          (re-matches #".Rule." text)
          (re-matches #".规则." text)
          (re-matches #".룰." text)
          (re-matches #".규칙." text)) :rule
      :else
      (let [b (subs text 0 1)]
        (case b
          "\u201C" (when (not= language "en")
                     :mention)
          "「" (when (not= language "en")
                 :mention)
          "\uFF62" (when (not= language "en")
                     :mention)
          "【" (when (not= language "en")
                 :timing)
          "〔" (when (not= language "en")
                 (if (= language "ko")
                   :precondition
                   :timing))
          "["  (when (not= language "en")
                 :precondition)
          "［" (when (not= language "en")
                 :precondition)
          "{" :precondition
          "<"  (when (not= language "zh-Hans")
                 :keyword-effect)
          "≪"  :keyword-effect
          "〈" :keyword-effect
          "《" :keyword-effect
          "＜" :keyword-effect
          nil)))))

(defn icons-in-text
  [text language]
  (let [re-escape-map {\[ "\\["
                       \] "\\]"
                       \( "\\("
                       \) "\\)"
                       \{ "\\{"
                       \} "\\}"}
        brackets (cond-> [;; squares
                          ["\u3010" "\u3011"]
                          ["[" "]"]
                          ["\u3014" "\u3015"]
                          ["\uFF3B" "\uFF3D"]
                          ["{" "}"]
                          ;; angles
                          ["\u226A" "\u226B"]
                          ["\u3008" "\u3009"]
                          ["\u300A" "\u300B"]
                          ["\uFF1C" "\uFF1E"]
                          ["\u27E8" "\u27E9"]
                          ;; quotes
                          #_["\u201C" "\u201D"]]
                   (not= language "zh-Hans")
                   (conj ["<" ">"])
                   (= language "zh-Hans")
                   (conj ["\u300C" "\u300D"]
                         ["\uFF62" "\uFF63"]))
        brackets-re (->> brackets
                         (map (fn [[open close]]
                                (format "%s[^%s]+%s"
                                        (string/escape open re-escape-map)
                                        (string/escape close re-escape-map)
                                        (string/escape close re-escape-map))))
                         (concat (when (or (= language "en")
                                           (= language "zh-Hans"))
                                   ["\\u201C[^\\u201D\\u3011\\u300D\\uFF63\\]\\uFF3D\\u3015\\uFF1E>]+\\u201D"])
                                 (when (or (= language "ja")
                                           (= language "ko"))
                                   ["\\u300C[^\\u201D\\u3011\\u300D\\uFF63\\]\\uFF3D\\u3015\\uFF1E>]+\\u300D"
                                    "\\uFF62[^\\u201D\\u3011\\u300D\\uFF63\\]\\uFF3D\\u3015\\uFF1E>]+\\uFF63"]))
                         (string/join "|")
                         re-pattern)]
    (re-seq brackets-re text)))

(defn process-icons
  [cards]
  (let [en-mentions (->> cards
                         (filter (fn [{:card/keys [language]}]
                                   (= language "en")))
                         (mapcat (fn [card]
                                   (remove nil?
                                           (concat [(:card/number card)
                                                    (:card/name card)
                                                    (:card/form card)]
                                                   (some-> (:card/attribute card)
                                                           (string/split #"/"))
                                                   (some-> (:card/type card)
                                                           (string/split #"/"))))))
                         set)
        card-groups
        (->> cards
             (sort-by (juxt (comp (fn [releases]
                                    (or (some->> releases
                                                 (sort-by :release/date)
                                                 first
                                                 :release/date)
                                        (Date.)))
                                  :card/releases)
                            :card/number
                            :card/parallel-id))
             (group-by :card/number)
             vals
             (sort-by (juxt (fn [cards]
                              (let [releases (->> cards
                                                  (mapcat :card/releases)
                                                  (sort-by :release/date))]
                                (or (some->> releases
                                             (sort-by :release/date)
                                             first
                                             :release/date)
                                    (Date.))))
                            (fn [cards]
                              (get (first cards) :card/number))
                            (fn [cards]
                              (get (first cards) :card/parallel-id)))))
        card-icons
        (fn [{:card/keys [id language] :as card}]
          (mapcat (fn [field]
                    (when-let [text (get card field)]
                      (->> (icons-in-text text language)
                           (map-indexed
                            (fn [idx text]
                              {:icon/id (-> id
                                            (string/replace "card/"
                                                            "icon/")
                                            (str "_"
                                                 (name field)
                                                 "_"
                                                 idx))
                               :icon/type (text->icon-type text
                                                           language)
                               :icon/index idx
                               :icon/text text
                               :icon/field field})))))
                  [:card/effect
                   :card/inherited-effect
                   :card/security-effect]))]
    (->> (reduce (fn [{:keys [translations] :as accl} card-group]
                   (let [cards (map (fn [card]
                                      (let [icons (card-icons card)]
                                        (cond-> card
                                          (seq icons)
                                          (assoc :card/icons icons))))
                                    card-group)
                         without-brackets (fn [s] (subs s 1 (dec (count s))))
                         ja-map
                         (some->> cards
                                  (filter (fn [{:card/keys [language]}]
                                            (= language "ja")))
                                  first
                                  :card/icons
                                  (reduce (fn [m {:icon/keys [index text field]
                                                 :as icon}]
                                            (let [ja-text (without-brackets text)]
                                              (-> m
                                                  (assoc [field ja-text index]
                                                         icon)
                                                  (assoc [field ja-text]
                                                         icon)
                                                  (assoc [field index]
                                                         icon))))
                                          {}))
                         en-icons
                         (some->> cards
                                  (filter (fn [{:card/keys [language]}]
                                            (= language "en")))
                                  first
                                  :card/icons
                                  (remove (fn [{icon-type :icon/type
                                               :icon/keys [text]}]
                                            (let [text (without-brackets text)]
                                              (or icon-type
                                                  (get translations text)
                                                  (some (fn [s]
                                                          (= s text))
                                                        en-mentions))))))
                         translations
                         (reduce (fn [m {:icon/keys [text
                                                    index
                                                    field]}]
                                   (let [text (without-brackets text)
                                         ja-text (get m text)
                                         {icon-type :icon/type
                                          matched-ja-text :icon/text}
                                         (or (get ja-map [field ja-text index])
                                             (get ja-map [field ja-text])
                                             (get ja-map [field index]))]
                                     (cond-> m
                                       (and matched-ja-text
                                            (not (get m text))
                                            (or (= icon-type :timing)
                                                (= icon-type :precondition)))
                                       (assoc text
                                              (-> matched-ja-text
                                                  without-brackets)))))
                                 translations
                                 en-icons)
                         update-icons
                         (fn [icons]
                           (->> icons
                                (map (fn [{:icon/keys [text index field]
                                          :as icon}]
                                       (let [text (without-brackets text)
                                             ja-text (get translations text)
                                             {icon-type :icon/type}
                                             (or (get ja-map
                                                      [field ja-text index])
                                                 (get ja-map
                                                      [field ja-text]))
                                             icon-type
                                             (if (contains? #{:timing
                                                              :precondition
                                                              :mention}
                                                            icon-type)
                                               icon-type
                                               :mention)]
                                         (cond-> icon
                                           (nil? (:icon/type icon))
                                           (assoc :icon/type
                                                  (or icon-type
                                                      :mention))))))))
                         cards
                         (map (fn [{:card/keys [icons] :as card}]
                                (cond-> card
                                  (some (fn [{icon-type :icon/type}]
                                          (nil? icon-type))
                                        icons)
                                  (update :card/icons
                                          update-icons)))
                              cards)]
                     (-> accl
                         (assoc :translations translations)
                         (update :cards concat cards))))
                 {:translations {"DeathX" "デクス"
                                 "Familiar" "使い魔"
                                 "Animal" "獣"
                                 "Justimon" "ジャスティモン"
                                 "Sovereign" "獣"
                                 "Imperialdramon" "インペリアルドラモン"}
                  :cards []}
                 card-groups)
         :cards)))
