(ns dcg.db.card.errata
  (:require
   [clojure.string :as string]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.card.repair :as repair]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.text ParseException SimpleDateFormat]
   [java.net URI]))

(defn- partition-at
  [f coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (let [run (cons (first s) (take-while #(not (f %)) (rest s)))]
       (cons run (partition-at f (drop (count run) s)))))))

(def ^:private repair
  {"BT8-110" (fn [s]
               (string/replace s "[Security]You" "[Security] You"))
   "BT10-093" (fn [s]
                (string/replace s
                                "[Your turn] [Once per turn]"
                                "[Your Turn][Once Per Turn]"))
   "BT14-023" (fn [s]
                (string/replace s "(Once Per Turn)" "[Once Per Turn]"))
   "EX3-001" (fn [s]
               (string/replace s
                               "[All Turns] [Once Per Turn] When this Digimon with [Dramon] or [Examon] in its name becomes unsuspended, this Digimon gets +1000 for the turn."
                               "[All Turns][Once Per Turn] When this Digimon with [Dramon] or [Examon] in its name becomes unsuspended, this Digimon gets +1000 DP for the turn."))
   "EX3-008" (fn [s]
               (string/replace s
                               "You may DNA digivolve this Digimon and one of your other Digimon may DNA digivolve into a Digimon card in your hand for the cost."
                               "You may DNA digivolve this Digimon and one of your other Digimon in play into a Digimon card in your hand for its DNA digivolve cost."))
   "EX3-057" (fn [s]
               (string/replace s "3000 or" "3000 DP or"))})

(defmulti errata
  (fn [{:origin/keys [language card-image-language] :as params}]
    (and (not card-image-language)
         language)))

(defmethod errata :default [_] nil)

(defmethod errata "ja"
  [{:origin/keys [url language] :as params}]
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
                          (catch ParseException e nil))
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
               {})))

(defmethod errata "en"
  [{:origin/keys [url language] :as params}]
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
       (partition-at #(= (:tag %) :h4))
       (mapcat (fn [elements]
                 (let [div {:type :element
                            :tag :div
                            :content elements}
                       card-numbers (some->> (select/select (select/tag :h4) div)
                                             first
                                             card-utils/text-content
                                             (re-seq card-utils/card-number-re))
                       date-string (some->> (select/select (select/tag :h4) div)
                                            first :content first)
                       date (try (.parse (SimpleDateFormat. "MM.dd.yy") date-string)
                                 (catch ParseException e nil))
                       text-fixes (fn [s]
                                    (-> s
                                        (string/replace #"^Start of Your Main Phase\]"
                                                        "[Start of Your Main Phase]")
                                        (string/replace #"^Before\n\s*" "")
                                        (string/replace #"^After\n\s*" "")
                                        (string/replace "⟨" "＜")
                                        (string/replace "⟩" "＞")))
                       error (->> (select/select (select/class "beforeCol") div)
                                  (map (comp text-fixes
                                             card-utils/text-content)))
                       fixed (->> (select/select (select/class "afterCol") div)
                                  (map (comp text-fixes
                                             card-utils/text-content)))
                       notes (->> (select/select (select/tag :dd) div)
                                  (map card-utils/text-content)
                                  (string/join "\n"))]
                   (map-indexed
                    (fn [idx number]
                      (let [repair-fn (get repair number)]
                        (cond-> {:errata/id (format "errata/%s_%s"
                                                    language number)
                                 :errata/language language
                                 :errata/date date
                                 :errata/card-number number
                                 :errata/error (cond-> (nth error idx nil)
                                                 repair-fn
                                                 repair-fn)
                                 :errata/correction (cond-> (nth fixed idx nil)
                                                      repair-fn
                                                      repair-fn)}
                          (not (string/blank? notes))
                          (assoc :errata/notes notes))))
                    card-numbers))))
       (reduce (fn [accl {:errata/keys [language card-number] :as errata}]
                 (assoc-in accl [card-number language]
                           (dissoc errata
                                   :errata/card-number
                                   :errata/language)))
               {})))
