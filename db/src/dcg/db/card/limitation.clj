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

(defn- card-numbers
  [elements]
  (map (fn [element]
         (->> (card-utils/text-content element)
              string/split-lines
              first
              (re-find card-utils/card-number-re)))
       elements))

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

(defn- elements->limitations
  [{:origin/keys [language] :as origin} elements]
  (->> elements
       (mapcat (fn [el]
                 (select/select
                  (select/or (select/tag :h4)
                             (select/tag :h5)
                             (select/tag :dd)
                             (select/class "noticeFrame")
                             (select/class "baseTxt"))
                  el)))
       (utils/partition-at (fn [{:keys [tag] :as el}]
                             (and (= tag :h4)
                                  (->> el
                                       card-utils/text-content
                                       (parse-date origin)))))
       (mapcat
        (fn [[title & elements]]
          (let [title (card-utils/text-content title)
                date (parse-date origin title)
                parent-limitation (limitation-type origin title)]
            (->> elements
                 (utils/partition-at (fn [{:keys [tag]}]
                                       (or (= tag :h4)
                                           (= tag :h5))))
                 (mapcat (fn [[subtitle & elements]]
                           (let [subtitle (card-utils/text-content subtitle)
                                 limitation (or parent-limitation
                                                (limitation-type origin
                                                                 subtitle))
                                 allowance
                                 (case limitation
                                   :banned-pair 0
                                   :ban 0
                                   :restrict (some->> subtitle
                                                      (re-find #"[0-9]+")
                                                      parse-long)
                                   nil)
                                 div {:type :element
                                      :tag :div
                                      :content elements}
                                 numbers
                                 (->> div
                                      (select/select
                                       (select/or
                                        (select/tag :dd)
                                        (select/child
                                         (select/has-child
                                          (select/and
                                           (select/class "noticeArea")
                                           (select/class "isBase")))
                                         (select/class "mt_s")
                                         (select/class "noticeList")
                                         (select/tag :li))))
                                      card-numbers)
                                 card-pairs
                                 (->> div
                                      (select/select
                                       (select/child
                                        (select/has-child
                                         (select/and
                                          (select/class "noticeArea")
                                          (select/class "isRed")))
                                        (select/class "mt_s")
                                        (select/class "noticeList")
                                        (select/tag :li)))
                                      card-numbers
                                      (into []))
                                 note (->> elements
                                           last
                                           card-utils/text-content)]
                             (map (fn [number]
                                    (let [id (format "limitation/%s_%s"
                                                     language
                                                     number)]
                                      (cond-> {:limitation/id id
                                               :limitation/type limitation
                                               :limitation/note note
                                               :limitation/card-number number
                                               :limitation/language language
                                               :limitation/date date}
                                        allowance
                                        (assoc :limitation/allowance allowance)
                                        (seq card-pairs)
                                        (assoc :limitation/paired-card-numbers
                                               card-pairs))))
                                  numbers))))))))
       set
       (sort-by :limitation/date)
       (reduce (fn [accl {:limitation/keys [card-number language]
                         :as limitation}]
                 (let [index (-> (get-in accl [card-number language]
                                         [])
                                 count)]
                   (-> accl
                       (update-in [card-number language]
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
                                            :limitation/date
                                            :limitation/note
                                            :limitation/card-number
                                            :limitation/language))))))
               {})))

(defmulti limitations :origin/language)

(defmethod limitations :default [_] nil)

(defmethod limitations "ja"
  [{:origin/keys [url] :as origin}]
  (->> (utils/http-get (str url "/rule/restriction_card/"))
       hickory/parse
       hickory/as-hickory
       (select/select (select/descendant (select/id "inner")
                                         (select/class "article")
                                         (select/and (select/tag :section)
                                                     (select/has-descendant
                                                      (select/class "baseTxt")))))
       (filter map?)
       (elements->limitations origin)))

(defmethod limitations "en"
  [{:origin/keys [url] :as origin}]
  (->> (utils/http-get (str url "/rule/restriction_card/"))
       hickory/parse
       hickory/as-hickory
       (select/select (select/descendant (select/id "inner")
                                         (select/class "article")))
       first :content (filter map?)
       (elements->limitations origin)))
