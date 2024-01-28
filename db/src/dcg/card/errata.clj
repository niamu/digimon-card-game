(ns dcg.card.errata
  (:require
   [clojure.string :as string]
   [dcg.card.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.text ParseException SimpleDateFormat]
   [java.net URI]))

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
                                     utils/text-content
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
                               utils/text-content)]
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
                                      (re-find utils/card-number-re src)))
                         error (some-> (select/select
                                        (select/descendant
                                         (select/follow-adjacent
                                          (select/and
                                           (select/tag "dt")
                                           (select/class "beforeCol"))
                                          (select/tag "dd")))
                                        card)
                                       first
                                       utils/text-content)
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
                                              utils/text-content)
                                      (some->> (select/select
                                                (select/descendant
                                                 (select/and
                                                  (select/tag "dd")
                                                  (select/class "mt_s")))
                                                card)
                                               (map utils/text-content)
                                               (string/join "\n")))]
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
       first
       :content
       (filter map?)
       rest
       (reduce (fn [accl element]
                 (let [title? (some->> (select/select
                                        (select/descendant (select/tag :h4))
                                        element)
                                       first)]
                   (if (or title?
                           (empty? accl))
                     (conj accl [element])
                     (update-in accl [(dec (count accl))] conj element))))
               [])
       (mapcat
        (fn [[heading & contents]]
          (let [date-string (some->> (select/select
                                      (select/descendant (select/tag :h4))
                                      heading)
                                     first
                                     :content
                                     first)
                date (try (.parse (SimpleDateFormat. "MM.dd.yy") date-string)
                          (catch ParseException e nil))]
            (->> (partition-all 2 contents)
                 (map (fn [[image text]]
                        [(-> (select/select (select/descendant
                                             (select/tag "img"))
                                            image)
                             first
                             (get-in [:attrs :src])
                             string/upper-case
                             (string/replace "_" "-")
                             (string/replace #"([A-Z]{2})0" "$1")
                             (as-> #__ src
                               (re-find utils/card-number-re src)))
                         (->> (select/select (select/descendant
                                              (select/tag "dl")
                                              (select/tag "dd")
                                              (select/or
                                               (select/class "beforeCol")
                                               (select/class "afterCol")))
                                             text)
                              (map (comp
                                    (fn [s]
                                      (-> s
                                          (string/replace "Before" "")
                                          (string/replace "After" "")
                                          string/trim
                                          (string/replace #"^Start of Your"
                                                          "[Start of Your")))
                                    utils/text-content)))
                         (->> (select/select
                               (select/or
                                (select/descendant
                                 (select/and
                                  (select/tag "dl")
                                  (select/has-child
                                   (select/find-in-text #"Errata Notes")))
                                 (select/tag "dd"))
                                (select/descendant (select/class "note")))
                               text)
                              (map utils/text-content)
                              (string/join "\n"))]))
                 (map (fn [[number [error correction] notes]]
                        (let [error (if (apply = (string/split-lines error))
                                      (first (string/split-lines error))
                                      error)
                              correction (if (apply = (string/split-lines correction))
                                           (first (string/split-lines correction))
                                           correction)]
                          {:errata/id (format "errata/%s_%s" language number)
                           :errata/language language
                           :errata/date date
                           :errata/card-number number
                           :errata/error error
                           :errata/correction correction
                           :errata/notes notes})))))))
       (reduce (fn [accl {:errata/keys [language card-number] :as errata}]
                 (assoc-in accl [card-number language]
                           (dissoc errata
                                   :errata/card-number
                                   :errata/language)))
               {})))
