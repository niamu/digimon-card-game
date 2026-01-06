(ns dcg.db.card.errata
  (:require
   [clojure.string :as string]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.card.repair :as repair]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select])
  (:import
   [java.text ParseException SimpleDateFormat]))

(def ^:private repair
  {"BT8-097" (fn [s]
               (string/replace s
                               "[Main] Your opponent can't play Digimon by effects until the end of their turn. Delete all of your opponent's Digimon with 6000 DP or less."
                               "[Main] Your opponent can't play Digimon by effects until the end of their next turn. Delete all of your opponent's Digimon with 6000 DP or less."))
   "BT8-110" (fn [s]
               (string/replace s "[Security]You" "[Security] You"))
   "BT9-067" (fn [s]
               (string/replace s ",and" ", and"))
   "BT10-093" (fn [s]
                (string/replace s
                                "[Your turn] [Once per turn] When you would play 1 level 4 or higher Digimon card with [Bagra Army] in its traits, by placing up to 3 purple Digimon cards from under your Tamers in the digivolution cards of the Digimon card played, reduce the memory cost of that Digimon by 2 for each card placed."
                                "[Your Turn][Once Per Turn] When you would play a level 4 or higher Digimon card with [Bagra Army] in its traits, by placing up to 3 purple Digimon cards from under your Tamers in the played Digimon card's digivolution cards, reduce the play cost of that Digimon by 2 for each card placed."))
   "BT14-002" (fn [s]
                (string/replace s "<Jamming>" "＜Jamming＞"))
   "BT14-023" (fn [s]
                (string/replace s "(Once Per Turn)" "[Once Per Turn]"))
   "BT14-091" (fn [s]
                (string/replace s "Then if" "Then, if"))
   "EX3-001" (fn [s]
               (string/replace s
                               "[All Turns] [Once Per Turn] When this Digimon with [Dramon] or [Examon] in its name becomes unsuspended, this Digimon gets +1000 for the turn."
                               "[All Turns][Once Per Turn] When this Digimon with [Dramon] or [Examon] in its name becomes unsuspended, this Digimon gets +1000 DP for the turn."))
   "EX3-008" (fn [s]
               (string/replace s
                               "You may DNA digivolve this Digimon and one of your other Digimon may DNA digivolve into a Digimon card in your hand for the cost."
                               "You may DNA digivolve this Digimon and one of your other Digimon in play into a Digimon card in your hand for its DNA digivolve cost."))
   "EX3-014" (fn [s]
               (-> s
                   (string/replace "[saur], or [Ceratopsian]"
                                   "[saur] or [Ceratopsian]")
                   (string/replace "3000 or less"
                                   "3000 DP or less")))
   "EX3-024" (fn [s]
               (-> s
                   (string/replace "[Start of Opponent's Main Phase] You may suspend 1 of your Digimon with [Dramon] or [Examon] in its name to force 1 of your opponent's Digimon to attack.\n[Start of Opponent's Main Phase] You may suspend 1 of your Digimon with [Dramon] or [Examon] in its name to force 1 of your opponent's Digimon to attack."
                                   "[Start of Opponent's Main Phase] You may suspend 1 of your Digimon with [Dramon] or [Examon] in its name to force 1 of your opponent's Digimon to attack.")
                   (string/replace "[Start of Opponent's Main Phase] By suspending 1 of your Digimon with [Dramon] or [Examon] in its name, your opponent attacks with 1 of their Digimon.\n[Start of Opponent's Main Phase] By suspending 1 of your Digimon with [Dramon] or [Examon] in its name, your opponent attacks with 1 of their Digimon."
                                   "[Start of Opponent's Main Phase] By suspending 1 of your Digimon with [Dramon] or [Examon] in its name, your opponent attacks with 1 of their Digimon.")))
   "EX3-030" (fn [s]
               (string/replace s "[Inherited Effect]" ""))
   "EX3-057" (fn [s]
               (-> s
                   (string/replace "If no Digimon is deleted by this effect, trash the top 2 cards of both players' decks"
                                   "If no Digimon was deleted by this effect, both players trash the top 2 cards of their decks.")))
   "EX3-063" (fn [s]
               (-> s
                   (string/replace "[When Digivolving] If DNA digivolving, your opponent chooses 1 of their Digimon. Delete all of their other Digimon. Then, ＜Blitz＞."
                                   "[When Digivolving] If DNA digivolving, your opponent chooses 1 of their Digimon and deletes the rest. Then, ＜Blitz＞.")))
   "LM-013" (fn [s]
              (string/replace s "…at" "…At"))
   "ST16-11" (fn [s]
               (string/replace s #"\.+" "."))})

(defmulti errata
  (fn [{:origin/keys [language card-image-language]}]
    (and (not card-image-language)
         language)))

(defmethod errata :default [_] nil)

(defmethod errata "ja"
  [{:origin/keys [url language]}]
  (let [number "LM-020"
        [error correction]
        (->> (utils/http-get (str url "/rule/revised/"))
             hickory/parse
             hickory/as-hickory
             (select/select (select/descendant
                             (select/id "inner")
                             (select/class "article")
                             (select/and
                              (select/tag :section)
                              (select/has-child
                               (select/and
                                (select/tag :h4)
                                (select/find-in-text (re-pattern number)))))
                             (select/class "noticeBox")
                             (select/tag :dd)))
             (map card-utils/text-content))
        rule-revisions {number
                        {language
                         {:errata/id (format "errata/%s_%s" language number)
                          :errata/date #inst "2024-08-01T00:00:00.000-00:00"
                          :errata/error error
                          :errata/correction correction}}}]
    (->> (utils/http-get (str url "/rule/rule_change/"))
         hickory/parse
         hickory/as-hickory
         (select/select (select/descendant (select/id "inner")
                                           (select/class "article")))
         first
         :content
         (filter map?)
         rest
         (reduce (fn [accl element]
                   (let [title? (some->> (select/select
                                          (select/descendant
                                           (select/class "wrap_redCol"))
                                          element)
                                         first)]
                     (if (or title?
                             (empty? accl))
                       (conj accl [element])
                       (update-in accl [(dec (count accl))] conj element))))
                 [])
         (mapcat
          (fn [[heading & contents]]
            (let [date-re #"[0-9]{4}\.[0-9]{1,2}\.[0-9]{2}"
                  date-string (some->> (select/select
                                        (select/descendant
                                         (select/class "wrap_redCol"))
                                        heading)
                                       first
                                       card-utils/text-content
                                       (re-find date-re))
                  date (try (.parse (SimpleDateFormat. "yyyy.MM.dd")
                                    date-string)
                            (catch ParseException _ nil))
                  cards (some->> (select/select
                                  (select/descendant
                                   (select/and (select/tag "article")
                                               (select/class "itemDate")))
                                  {:type :element
                                   :attrs {}
                                   :tag :div
                                   :content contents}))
                  notes (some->> (select/select
                                  (select/descendant
                                   (select/and
                                    (select/tag "p")
                                    (select/class "mb")
                                    (select/class "baseTxt")))
                                  {:type :element
                                   :attrs {}
                                   :tag :div
                                   :content contents})
                                 first
                                 card-utils/text-content)]
              (map (fn [card]
                     (let [number (-> (select/select (select/descendant
                                                      (select/tag "img"))
                                                     card)
                                      first
                                      (get-in [:attrs :src])
                                      string/upper-case
                                      (string/replace "_" "-")
                                      (string/replace #"([A-Z]{2})0" "$1")
                                      (as-> #__ src
                                        (re-find card-utils/card-number-re src)))
                           error (some-> (select/select
                                          (select/descendant
                                           (select/follow-adjacent
                                            (select/and
                                             (select/tag "dt")
                                             (select/class "beforeCol"))
                                            (select/tag "dd")))
                                          card)
                                         first
                                         card-utils/text-content
                                         repair/text-fixes)
                           correction (if error
                                        (some-> (select/select
                                                 (select/descendant
                                                  (select/follow-adjacent
                                                   (select/and
                                                    (select/tag "dt")
                                                    (select/class "afterCol"))
                                                   (select/tag "dd")))
                                                 card)
                                                first
                                                card-utils/text-content
                                                repair/text-fixes)
                                        (some->> (select/select
                                                  (select/descendant
                                                   (select/and
                                                    (select/tag "dd")
                                                    (select/class "mt_s")))
                                                  card)
                                                 (map card-utils/text-content)
                                                 (string/join "\n")
                                                 repair/text-fixes))]
                       (cond-> {:errata/id (format "errata/%s_%s"
                                                   language number)
                                :errata/language language
                                :errata/date date
                                :errata/card-number number
                                :errata/correction correction}
                         notes (assoc :errata/notes notes)
                         error (assoc :errata/error error))))
                   cards))))
         (reduce (fn [accl {:errata/keys [language card-number] :as errata}]
                   (assoc-in accl [card-number language]
                             (dissoc errata
                                     :errata/card-number
                                     :errata/language)))
                 rule-revisions))))

(defmethod errata "en"
  [{:origin/keys [url language]}]
  (let [number "LM-020"
        [error correction]
        (->> (utils/http-get (str url "/rule/revised/"))
             hickory/parse
             hickory/as-hickory
             (select/select (select/descendant
                             (select/id "inner")
                             (select/class "article")
                             (select/and
                              (select/tag :section)
                              (select/has-child
                               (select/and
                                (select/tag :h4)
                                (select/find-in-text (re-pattern number)))))
                             (select/class "noticeBox")
                             (select/tag :p)))
             (map card-utils/text-content))
        rule-revisions {number
                        {language
                         {:errata/id (format "errata/%s_%s"
                                             language number)
                          :errata/date #inst "2024-08-23T00:00:00.000-00:00"
                          :errata/error (string/replace error
                                                        "[Start of Opponent's Turn] Declare 1 card category. Then, reveal the top card of your opponent's deck. If it's of that category, this Digimon isn't affected by that category's effects for the turn. Return the revealed cards to the top or bottom of your opponent's deck."
                                                        "[Start of Opponent's Turn] Declare 1 card category. Then, reveal the top card of your opponent's deck. If that card is of the declared category, this Digimon isn't affected by the effects of that card category for the turn. Return the revealed card to the top or the bottom of your opponent's deck.")
                          :errata/correction correction}}}]
    (->> (utils/http-get (str url "/rule/errata_card/"))
         hickory/parse
         hickory/as-hickory
         (select/select (select/descendant (select/id "inner")
                                           (select/class "article")))
         first :content (filter map?) rest
         (mapcat (fn [el]
                   (select/select
                    (select/or (select/tag :h4)
                               (select/tag :img)
                               (select/class "beforeCol")
                               (select/class "afterCol")
                               (select/has-child
                                (select/find-in-text #"^Errata Notes$")))
                    el)))
         (utils/partition-at #(= (:tag %) :h4))
         (mapcat (fn [elements]
                   (let [div {:type :element
                              :tag :div
                              :content elements}
                         card-numbers (some->> div
                                               (select/select (select/tag :h4))
                                               first
                                               card-utils/text-content
                                               (re-seq card-utils/card-number-re))
                         date-string (some->> div
                                              (select/select (select/tag :h4))
                                              first :content first)
                         date (try (.parse (SimpleDateFormat. "MMM. dd, yyyy")
                                           date-string)
                                   (catch ParseException _ nil))
                         text-fixes (fn [s]
                                      (-> s
                                          (string/replace "’" "'")
                                          (string/replace #"^Start of Your Main Phase\]"
                                                          "[Start of Your Main Phase]")
                                          (string/replace "⟨" "＜")
                                          (string/replace "⟩" "＞")))
                         filter-content
                         (fn [element]
                           (update element :content
                                   (fn [content]
                                     (->> content
                                          (remove
                                           (fn [el]
                                             (and (map? el)
                                                  (or (= (:tag el) :b)
                                                      (= (get-in el [:attrs
                                                                     :class])
                                                         "txtExBold")))))))))
                         error (->> div
                                    (select/select (select/class "beforeCol"))
                                    (map (comp text-fixes
                                               card-utils/text-content
                                               filter-content)))
                         fixed (->> div
                                    (select/select (select/class "afterCol"))
                                    (map (comp text-fixes
                                               card-utils/text-content
                                               filter-content)))
                         notes (->> (select/select (select/tag :dd) div)
                                    (map card-utils/text-content)
                                    (string/join "\n"))]
                     (->> card-numbers
                          (map-indexed
                           (fn [idx number]
                             (let [repair-fn (get repair number)]
                               (cond-> {:errata/id (format "errata/%s_%s"
                                                           language number)
                                        :errata/language language
                                        :errata/date date
                                        :errata/card-number number
                                        :errata/error (cond-> (nth error idx
                                                                   (first error))
                                                        repair-fn
                                                        repair-fn)
                                        :errata/correction (cond-> (nth fixed idx
                                                                        (first fixed))
                                                             repair-fn
                                                             repair-fn)}
                                 (not (string/blank? notes))
                                 (assoc :errata/notes notes)))))
                          (remove (fn [{:errata/keys [error correction]}]
                                    (or (string/blank? error)
                                        (string/blank? correction))))))))
         (reduce (fn [accl {:errata/keys [language card-number] :as errata}]
                   (assoc-in accl [card-number language]
                             (dissoc errata
                                     :errata/card-number
                                     :errata/language)))
                 rule-revisions))))

(defmethod errata "zh-Hans"
  [{:origin/keys [language]}]
  ;; Manually transcribed from: https://digimoncard.cn/ruleinfo?id=55
  (let [number "LM-020"]
    {number
     {language {:errata/id (format "errata/%s_%s" language number)
                :errata/date #inst "2024-09-12T00:00:00.000-00:00"
                :errata/error "【对方的回合开始时】宣言一种卡牌类别。此后,翻开对方卡组最上方的1张卡牌,若该卡牌为宣言的卡牌类别,则直到回合结束为止,此数码宝贝不受宣言卡牌类别的效果影响。将翻开的卡牌放回到对方的卡组最上方或最下方。"
                :errata/correction "【对方的回合开始时】宣言一种卡牌类别。此后,翻开对方卡组最上方的1张卡牌,若该卡牌为宣言 的卡牌类别,则直到回合结束为止,此数码宝贝不受到此数码宝贝以外的宣言的卡牌类别的效果影响。将翻开的卡牌放回到对方的卡组最上方或最下方。"}}}))
