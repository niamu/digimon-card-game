(ns dcg.db.card.highlight
  (:require
   [clojure.string :as string])
  (:import
   [java.util Date]))

(defn- text->highlight-type
  [text language]
  (when text
    (cond
      (or (string/includes? text "デジクロス")
          (string/includes? text "DigiXros")
          (string/includes? text "数码合体")
          (string/includes? text "디지크로스")) :digixros
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
          "<"  (when (not= language "zh-Hans")
                 :keyword-effect)
          "≪"  :keyword-effect
          "〈" :keyword-effect
          "《" :keyword-effect
          "＜" :keyword-effect
          nil)))))

(defn- highlights-in-text
  [text language]
  (let [re-escape-map {\[ "\\["
                       \] "\\]"
                       \( "\\("
                       \) "\\)"}
        brackets (cond-> [;; squares
                          ["\u3010" "\u3011"]
                          ["[" "]"]
                          ["\u3014" "\u3015"]
                          ["\uFF3B" "\uFF3D"]
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
        ignore-parens "(?![^（]*）)(?![^\\(]*\\))"
        brackets-re (->> brackets
                         (map (fn [[open close]]
                                (format (str "%s[^%s]+%s" #_ignore-parens)
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

(defn process-highlights
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
        card-highlights
        (fn [{:card/keys [id language] :as card}]
          (mapcat (fn [field]
                    (when-let [text (get card field)]
                      (->> (highlights-in-text text language)
                           (map-indexed
                            (fn [idx text]
                              {:highlight/id (-> id
                                                 (string/replace "card/"
                                                                 "highlight/")
                                                 (str "_"
                                                      (name field)
                                                      "_"
                                                      idx))
                               :highlight/type (text->highlight-type text
                                                                     language)
                               :highlight/index idx
                               :highlight/text text
                               :highlight/field field})))))
                  [:card/effect
                   :card/inherited-effect
                   :card/security-effect]))]
    (->> (reduce (fn [{:keys [translations] :as accl} card-group]
                   (let [cards (map (fn [card]
                                      (let [highlights (card-highlights card)]
                                        (cond-> card
                                          (seq highlights)
                                          (assoc :card/highlights highlights))))
                                    card-group)
                         without-brackets (fn [s] (subs s 1 (dec (count s))))
                         ja-highlights
                         (some->> cards
                                  (filter (fn [{:card/keys [language]}]
                                            (= language "ja")))
                                  first
                                  :card/highlights
                                  (filter (fn [{highlight-type :highlight/type}]
                                            (contains? #{:timing
                                                         :precondition}
                                                       highlight-type)))
                                  (map (comp without-brackets :highlight/text)))
                         ja-map
                         (some->> cards
                                  (filter (fn [{:card/keys [language]}]
                                            (= language "ja")))
                                  first
                                  :card/highlights
                                  (reduce (fn [m {:highlight/keys [index text field]
                                                 :as highlight}]
                                            (let [ja-text (without-brackets text)]
                                              (-> m
                                                  (assoc [field ja-text index]
                                                         highlight)
                                                  (assoc [field ja-text]
                                                         highlight)
                                                  (assoc [field index]
                                                         highlight))))
                                          {}))
                         en-highlights
                         (some->> cards
                                  (filter (fn [{:card/keys [language]}]
                                            (= language "en")))
                                  first
                                  :card/highlights
                                  (remove (fn [{highlight-type :highlight/type
                                               :highlight/keys [text]}]
                                            (let [text (without-brackets text)]
                                              (or highlight-type
                                                  (get translations text)
                                                  (some (fn [s]
                                                          (= s text))
                                                        en-mentions))))))
                         translations
                         (reduce (fn [m {:highlight/keys [text
                                                         index
                                                         field]
                                        :as highlight}]
                                   (let [text (without-brackets text)
                                         ja-text (get m text)
                                         {highlight-type :highlight/type
                                          matched-ja-text :highlight/text}
                                         (or (get ja-map [field ja-text index])
                                             (get ja-map [field ja-text])
                                             (get ja-map [field index]))]
                                     (cond-> m
                                       (and matched-ja-text
                                            (not (get m text))
                                            (or (= highlight-type :timing)
                                                (= highlight-type :precondition)))
                                       (assoc text
                                              (-> matched-ja-text
                                                  without-brackets)))))
                                 translations
                                 en-highlights)
                         update-highlights
                         (fn [highlights]
                           (->> highlights
                                (map (fn [{:highlight/keys [text index field]
                                          :as highlight}]
                                       (let [text (without-brackets text)
                                             ja-text (get translations text)
                                             {highlight-type :highlight/type}
                                             (or (get ja-map
                                                      [field ja-text index])
                                                 (get ja-map
                                                      [field ja-text]))
                                             highlight-type
                                             (if (contains? #{:timing
                                                              :precondition
                                                              :mention}
                                                            highlight-type)
                                               highlight-type
                                               :mention)]
                                         (cond-> highlight
                                           (nil? (get highlight
                                                      :highlight/type))
                                           (assoc :highlight/type
                                                  (or highlight-type
                                                      :mention))))))))
                         cards
                         (map (fn [{:card/keys [highlights] :as card}]
                                (cond-> card
                                  (some (fn [{highlight-type :highlight/type}]
                                          (nil? highlight-type))
                                        highlights)
                                  (update :card/highlights
                                          update-highlights)))
                              cards)]
                     (-> accl
                         (assoc :translations translations)
                         (update :cards concat cards))))
                 {:translations {"DeathX" "デクス"
                                 "Familiar" "使い魔"
                                 "Animal" "獣"
                                 "Justimon" "ジャスティモン"
                                 "Sovereign" "獣"}
                  :cards []}
                 card-groups)
         :cards)))
