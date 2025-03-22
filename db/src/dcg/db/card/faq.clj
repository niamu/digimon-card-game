(ns dcg.db.card.faq
  (:require
   [clojure.string :as string]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select])
  (:import
   [java.text ParseException SimpleDateFormat]))

(defmulti product-faqs
  (fn [{:origin/keys [language]} _]
    language))

(defmethod product-faqs :default
  [{:origin/keys [url language]} form-params]
  (->> (utils/http-post (str url "/rule/") form-params {})
       hickory/parse
       hickory/as-hickory
       (select/select (select/descendant
                       (select/class "qaResult")
                       (select/and
                        (select/class "qa_box")
                        (select/has-child
                         (select/class "qa_category")))))
       (map (fn [qa]
              (let [number (->> qa
                                (select/select (select/tag :dt))
                                first
                                card-utils/text-content
                                (re-find card-utils/card-number-re))
                    date-string (->> qa
                                     (select/select
                                      (select/descendant
                                       (select/class "questions")
                                       (select/tag :dd)))
                                     first
                                     :content
                                     last
                                     card-utils/text-content)
                    date (when date-string
                           (try (.parse (SimpleDateFormat. (case language
                                                             "ja" "yyyy/MM/dd"
                                                             "MMM. dd, yyyy"))
                                        date-string)
                                (catch ParseException _ nil)))
                    questions (->> qa
                                   (select/select (select/descendant
                                                   (select/class "questions")
                                                   (select/tag :dd)))
                                   (map (comp (fn [s]
                                                (cond-> s
                                                  date-string
                                                  (string/replace date-string
                                                                  "")))
                                              card-utils/normalize-string
                                              card-utils/text-content)))
                    answers (->> qa
                                 (select/select (select/descendant
                                                 (select/class "answer")
                                                 (select/tag :dd)))
                                 (map (comp card-utils/normalize-string
                                            card-utils/text-content)))]
                (->> (interleave questions
                                 answers)
                     (partition-all 2)
                     (reduce (fn [accl [question answer]]
                               (update-in accl [number language]
                                          (fnil conj [])
                                          (cond-> {:faq/question question
                                                   :faq/answer answer}
                                            date
                                            (assoc :faq/date date))))
                             {})))))
       (apply merge)))

(defmethod product-faqs "ko"
  [{:origin/keys [url language]} form-params]
  (->> (utils/http-get url form-params)
       hickory/parse
       hickory/as-hickory
       (select/select (select/descendant
                       (select/class "qaResult")
                       (select/and
                        (select/class "qa_box")
                        (select/has-child
                         (select/class "qa_category")))))
       (map (fn [qa]
              (let [number (-> (->> qa
                                    (select/select (select/tag :dt))
                                    first
                                    card-utils/text-content)
                               (string/replace " -" "-")
                               (as-> s (re-find card-utils/card-number-re s)))
                    date-string (->> qa
                                     (select/select
                                      (select/descendant
                                       (select/class "questions")
                                       (select/tag :dd)))
                                     (map card-utils/text-content)
                                     string/join
                                     (re-find #"\d{4}/\d{2}/\d{2}.*$"))
                    date (when date-string
                           (try (.parse (SimpleDateFormat. (case language
                                                             "en" "MMM. dd, yyyy"
                                                             "yyyy/MM/dd"))
                                        date-string)
                                (catch ParseException _ nil)))
                    questions (->> qa
                                   (select/select (select/descendant
                                                   (select/class "questions")
                                                   (select/tag :dd)))
                                   (map (fn [s]
                                          (some-> s
                                                  card-utils/text-content
                                                  card-utils/normalize-string
                                                  (cond-> #__
                                                    date-string
                                                    (string/replace date-string
                                                                    ""))))))
                    answers (->> qa
                                 (select/select (select/descendant
                                                 (select/class "answer")
                                                 (select/tag :dd)))
                                 (map (fn [s]
                                        (some-> s
                                                card-utils/text-content
                                                card-utils/normalize-string))))]
                (->> (interleave questions
                                 answers)
                     (partition-all 2)
                     (reduce (fn [accl [question answer]]
                               (if (and question answer)
                                 (update-in accl [number language]
                                            (fnil conj [])
                                            (cond-> {:faq/question question
                                                     :faq/answer answer}
                                              date
                                              (assoc :faq/date date)))
                                 accl))
                             {})))))
       (apply merge)))

(defmulti faqs :origin/language)

(defmethod faqs :default
  [{:origin/keys [url] :as origin}]
  (let [form (->> (utils/http-get (str url "/rule/"))
                  hickory/parse
                  hickory/as-hickory
                  (select/select
                   (select/child (select/class "search_form")
                                 (select/has-child
                                  (select/and
                                   (select/tag :input)
                                   (select/attr :name
                                                (partial = "is_card_search"))))))
                  first)
        products (->> (select/select
                       (select/descendant (select/and
                                           (select/tag :select)
                                           (select/attr :name
                                                        (partial = "prodid")))
                                          (select/tag :option))
                       form)
                      (map (comp :value :attrs))
                      (remove empty?))
        form-params (->> (select/select (select/or (select/tag :input)
                                                   (select/tag :select))
                                        form)
                         (map (fn [{{field-name :name
                                    :keys [value]} :attrs}]
                                {field-name value}))
                         (apply merge))]
    (reduce (fn [accl product]
              (merge accl
                     (product-faqs origin
                                   (assoc form-params "prodid" product))))
            {}
            products)))

(defmethod faqs "ko"
  [{:origin/keys [url] :as origin}]
  (let [http-opts (utils/cupid-headers (str url "/qna/"))
        form (->> (utils/http-get (str url "/qna/") http-opts)
                  hickory/parse
                  hickory/as-hickory
                  (select/select
                   (select/child (select/class "search_form")
                                 (select/has-child
                                  (select/and
                                   (select/tag :input)
                                   (select/attr :name
                                                (partial = "is_card_search"))))))
                  first)
        products (->> (select/select
                       (select/descendant (select/and
                                           (select/tag :select)
                                           (select/attr :name
                                                        (partial = "category")))
                                          (select/tag :option))
                       form)
                      (map (comp :value :attrs))
                      (remove empty?))
        form-params (->> (select/select (select/or (select/tag :input)
                                                   (select/tag :select))
                                        form)
                         (map (fn [{{field-name :name
                                    :keys [value]} :attrs}]
                                {field-name value}))
                         (apply merge))]
    (->> products
         (mapcat (fn [product]
                   (let [options (merge http-opts
                                        {:query-params (assoc form-params
                                                              "category"
                                                              product)})
                         pages (->> (utils/http-get url options)
                                    hickory/parse
                                    hickory/as-hickory
                                    (select/select (select/descendant
                                                    (select/class "pager_card")
                                                    (select/tag :a)))
                                    (map card-utils/text-content))]
                     (map (fn [page]
                            (product-faqs origin
                                          (assoc-in options
                                                    [:query-params "page"]
                                                    page)))
                          pages))))
         (apply merge-with merge))))

(defmethod faqs "zh-Hans" [_] nil)

(comment
  (->> (pmap faqs [{:origin/url "https://digimoncard.com"
                    :origin/language "ja"}
                   {:origin/url "https://world.digimoncard.com"
                    :origin/language "en"}
                   {:origin/url "https://www.digimoncard.cn"
                    :origin/language "zh-Hans"}
                   {:origin/url "https://digimoncard.co.kr"
                    :origin/language "ko"}])
       doall
       (apply merge-with merge))

  )
