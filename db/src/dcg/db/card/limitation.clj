(ns dcg.db.card.limitation
  (:require
   [clojure.string :as string]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select])
  (:import
   [java.text SimpleDateFormat]
   [java.time LocalDate ZoneOffset]
   [java.util Date]))

(defmulti parse-date
  (fn [{:origin/keys [language]} _]
    language))

(defmethod parse-date "ja"
  [_ s]
  (let [date-matcher #"(\d+)[年년]\s?(\d+)[月월]\s?(\d+)?[日일]?"
        [year month day] (some->> (re-find date-matcher s)
                                  rest
                                  (map #(parse-long (or % "1"))))]
    (when (and year month day)
      (as-> (LocalDate/of ^int year
                          ^int month
                          ^int day) d
        (.atStartOfDay ^LocalDate d)
        (.toInstant d ZoneOffset/UTC)
        (Date/from d)))))

(defmethod parse-date "en"
  [_ s]
  (or (some->> (re-find #"\w+ \d+, [0-9]{4}" s)
               (.parse (SimpleDateFormat. "MMM dd, yyyy")))
      (when (string/includes? s "BT-08")
        #inst "2022-05-13T04:00:00.000-00:00")))

(defmethod parse-date :default [_ _] nil)

(defmulti limitation-type
  (fn [{:origin/keys [language]} _]
    language))

(defmethod limitation-type "ja"
  [_ s]
  (let [s (string/lower-case s)]
    (cond
      (string/includes? s "制限より解除")  :unrestrict
      (string/starts-with? s "禁止カード") :ban
      (string/starts-with? s "禁止ペア")   :banned-pair
      (string/starts-with? s "制限カード") :restrict
      :else                                nil)))

(defmethod limitation-type "en"
  [_ s]
  (let [s (string/lower-case s)]
    (cond
      (string/includes? s "lifted")          :unrestrict
      (string/starts-with? s "banned cards") :ban
      (string/starts-with? s "banned pair")  :banned-pair
      (string/starts-with? s "restricted")   :restrict
      :else                                  nil)))

(defmethod limitation-type :default [_ _] nil)

(defn currently-affected-cards
  [{:origin/keys [language] :as origin} section]
  (->> section
       (select/select
        (select/descendant
         (select/or (select/tag :h5)
                    (select/class "noticeFrame")
                    (select/class "num"))))
       (utils/partition-at (fn [{:keys [tag]}]
                             (= tag :h5)))
       (mapcat
        (fn [[title & cards]]
          (let [title (card-utils/text-content title)
                limitation-type (limitation-type origin title)
                allowance (case limitation-type
                            :banned-pair 0
                            :ban 0
                            :restrict (some->> title
                                               (re-find #"[0-9]+")
                                               parse-long)
                            nil)]
            (if (= limitation-type :banned-pair)
              (->> cards
                   (map (fn [section]
                          (let [[card-number & paired-card-numbers]
                                (->> section
                                     (select/select
                                      (select/descendant
                                       (select/class "txtLink")))
                                     (map (comp
                                           (partial re-find
                                                    card-utils/card-number-re)
                                           card-utils/text-content)))]
                            {:limitation/id (format "limitation/%s_%s"
                                                    language
                                                    card-number)
                             :limitation/type :banned-pair
                             :limitation/allowance 0
                             :limitation/card-number card-number
                             :limitation/paired-card-numbers (into [] paired-card-numbers)
                             :limitation/language language}))))
              (->> cards
                   (map (comp
                         (fn [card-number]
                           (let [id (format "limitation/%s_%s"
                                            language
                                            card-number)]
                             {:limitation/id id
                              :limitation/type limitation-type
                              :limitation/allowance allowance
                              :limitation/card-number card-number
                              :limitation/language language}))
                         card-utils/text-content)))))))))

(defmulti limitations :origin/language)

(defmethod limitations :default
  [{:origin/keys [url language] :as origin}]
  (letfn [(process-sections [[new more currently-affected]]
            #_(let [current-by-number (->> currently-affected
                                           (currently-affected-cards origin)
                                           (reduce (fn [accl {:limitation/keys [card-number]
                                                             :as limitation}]
                                                     (assoc accl card-number
                                                            limitation))
                                                   {}))]
                current-by-number)
            (->> {:type :element
                  :tag :div
                  :content (concat [new] [more])}
                 (select/select
                  (select/descendant
                   (select/or (select/tag :h4)
                              (select/tag :h5)
                              (select/or
                               (select/child
                                (select/tag :section)
                                (select/and (select/has-child
                                             (select/class "noticeArea"))
                                            (select/has-descendant
                                             (select/tag :dd))))
                               (select/child
                                (select/tag :div)
                                (select/and (select/class "noticeFrame")
                                            (select/has-descendant
                                             (select/class "noticeList"))))
                               (select/and
                                (select/tag :dd)
                                (select/not
                                 (select/descendant
                                  (select/has-child
                                   (select/class "noticeArea"))
                                  (select/tag :dd)))))
                              (select/class "baseTxt"))))
                 (utils/partition-at (fn [{:keys [tag]}]
                                       (= tag :h4)))
                 (mapcat (fn [[effective-date & contents]]
                           (let [date (->> effective-date
                                           card-utils/text-content
                                           (parse-date origin))]
                             (->> contents
                                  (utils/partition-at (fn [{:keys [tag]}]
                                                        (= tag :h5)))
                                  (mapcat (fn [cards]
                                            (let [[title cards] (if (= (:tag (first cards))
                                                                       :h5)
                                                                  [(->> (first cards)
                                                                        card-utils/text-content)
                                                                   (rest cards)]
                                                                  ["" cards])
                                                  limitation-type (or (limitation-type origin title)
                                                                      :unrestrict)
                                                  allowance (case limitation-type
                                                              :banned-pair 0
                                                              :ban 0
                                                              :restrict (some->> title
                                                                                 (re-find #"[0-9]+")
                                                                                 parse-long)
                                                              nil)]
                                              (if (= limitation-type :banned-pair)
                                                (->> cards
                                                     (utils/partition-at
                                                      (fn [{:keys [tag]}]
                                                        (= tag :div)))
                                                     (map (fn [[card-numbers & notes]]
                                                            (let [[card-number
                                                                   & paired-card-numbers]
                                                                  (->> card-numbers
                                                                       (select/select
                                                                        (select/descendant
                                                                         (select/or
                                                                          (select/tag :dd)
                                                                          (select/tag :li))))
                                                                       (map card-utils/text-content)
                                                                       (string/join "\n")
                                                                       (re-seq card-utils/card-number-re))
                                                                  id (format "limitation/%s_%s"
                                                                             language
                                                                             card-number)
                                                                  note (->> notes
                                                                            (map card-utils/text-content)
                                                                            (string/join "\n"))]
                                                              {:limitation/id id
                                                               :limitation/type limitation-type
                                                               :limitation/date date
                                                               :limitation/allowance allowance
                                                               :limitation/card-number card-number
                                                               :limitation/paired-card-numbers (into [] paired-card-numbers)
                                                               :limitation/language language
                                                               :limitation/note note}))))
                                                (->> cards
                                                     (utils/partition-at
                                                      (fn [{:keys [tag]}]
                                                        (= tag :dd)))
                                                     (map (fn [[dd & notes :as x]]
                                                            (let [card-number (->> dd
                                                                                   card-utils/text-content
                                                                                   (re-find card-utils/card-number-re))
                                                                  id (format "limitation/%s_%s"
                                                                             language
                                                                             card-number)
                                                                  note (->> notes
                                                                            (map card-utils/text-content)
                                                                            (string/join "\n"))]
                                                              (cond-> {:limitation/id id
                                                                       :limitation/type limitation-type
                                                                       :limitation/date date
                                                                       :limitation/card-number card-number
                                                                       :limitation/language language
                                                                       :limitation/note note}
                                                                allowance
                                                                (assoc :limitation/allowance
                                                                       allowance))))))))))))))
                 (reduce (fn [accl {:limitation/keys [date] :as limitation}]
                           (conj accl
                                 (cond-> limitation
                                   (and (contains? (set (keys limitation))
                                                   :limitation/date)
                                        (nil? date))
                                   (assoc :limitation/date
                                          (->> accl
                                               last
                                               :limitation/date)))))
                         [])
                 (sort-by :limitation/date)
                 (reduce (fn [accl {:limitation/keys [card-number language]
                                   :as limitation}]
                           (let [index (-> (get-in accl [card-number language]
                                                   [])
                                           count)]
                             (cond-> accl
                               (->> (get-in accl [card-number language] [])
                                    (filter (fn [{:limitation/keys [date]}]
                                              (= date (:limitation/date limitation))))
                                    seq
                                    not)
                               (-> (update-in [card-number language]
                                              (fnil conj [])
                                              (dissoc (update limitation
                                                              :limitation/id
                                                              str "_" index)
                                                      :limitation/card-number
                                                      :limitation/language))
                                   ;; English provides default limitations
                                   (cond-> #__
                                     (= language "en")
                                     (update-in [card-number :default]
                                                (fnil conj [])
                                                (dissoc (update limitation
                                                                :limitation/id
                                                                (fn [s]
                                                                  (-> s
                                                                      (string/replace
                                                                       (str language "_")
                                                                       "")
                                                                      (str "_" index))))
                                                        :limitation/note
                                                        :limitation/card-number
                                                        :limitation/language)))))))
                         {})))]
    (->> (utils/http-get (str url "/rule/restriction_card/"))
         hickory/parse
         hickory/as-hickory
         (select/select
          (select/child
           (select/id "inner")
           (select/class "article")
           (select/and
            (select/tag :section)
            (select/has-descendant
             (select/and
              (select/tag :img)
              (select/attr :src
                           (fn [s]
                             (->> (string/upper-case s)
                                  (re-find card-utils/card-number-re)))))))))
         process-sections)))

(defmethod limitations "zh-Hans" [_] nil)
(defmethod limitations "ko" [_] nil)
