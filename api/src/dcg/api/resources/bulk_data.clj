(ns dcg.api.resources.bulk-data
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.api.resources.card :as card]
   [dcg.api.resources.errors :as errors]
   [dcg.api.router :as router]
   [dcg.api.routes :as routes]
   [dcg.api.utils :as utils]
   [liberator.core :as liberator]
   [liberator.representation :as representation])
  (:import
   [java.time ZonedDateTime ZoneOffset]))

(defmacro all-files
  []
  (->> (file-seq (io/file (io/resource "public/bulk-data/")))
       (filter (fn [f]
                 (and (.isFile f)
                      (string/ends-with? (.getName f) ".json"))))
       sort
       (map (fn [f]
              (let [filename (.getName f)
                    [_ year month day hour minute second]
                    (re-find #"(\d{4})\-(\d{2})\-(\d{2})\-(\d{2})(\d{2})(\d{2})"
                             filename)
                    [year month day hour minute second]
                    (->> [year month day hour minute second]
                         (map parse-long))]
                {:filename filename
                 :path (string/replace (.getPath f)
                                       #".*/public"
                                       "")
                 :size (.length f)
                 :updated-at (-> (ZonedDateTime/of year
                                                   month
                                                   day
                                                   hour
                                                   minute
                                                   second
                                                   0
                                                   ZoneOffset/UTC)
                                 (.toInstant)
                                 utils/inst->iso8601)})))
       (into [])))

(liberator/defresource bulk-data-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :etag (fn [_] (->> (all-files)
                     (map :filename)
                     (string/join ",")
                     utils/sha))
   :handle-ok (fn [{request :request}]
                (->> (all-files)
                     (map (fn [{:keys [filename path size updated-at]}]
                            (let [prefix (-> filename
                                             (string/split #"-\d{4}")
                                             first)
                                  lmap {"en" "English"
                                        "ja" "Japanese"
                                        "zh-Hans" "Chinese (Simplified)"
                                        "ko" "Korean"}]
                              {:type "bulk-data"
                               :id (utils/sha filename)
                               :attributes
                               {:name (if (get lmap prefix)
                                        (str (get lmap prefix)
                                             " Cards")
                                        (->> (string/split prefix #"_")
                                             (map string/capitalize)
                                             (string/join " ")))
                                :size size
                                :content-type "application/vnd.api+json"
                                :updated-at updated-at}
                               :links
                               {:download (utils/update-asset-path path)}})))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :as-response (fn [data {representation :representation :as context}]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})

(defn export!
  []
  (let [bulk-data-dir "resources/public/bulk-data/"
        cards (card/all-cards)]
    ;; Clean bulk data directory
    (doseq [file (->> (file-seq (io/file bulk-data-dir))
                      (filter #(.isFile %)))]
      (io/delete-file file true))
    (doseq [[prefix filter-fn] (reduce (fn [accl {{id :id} :data}]
                                         (let [lang (->> (string/split id #"/")
                                                         (remove string/blank?)
                                                         first)]
                                           (assoc accl
                                                  lang
                                                  (fn [{{id :id} :data}]
                                                    (string/starts-with?
                                                     id
                                                     (str "/" lang "/"))))))
                                       {"all_cards" nil}
                                       cards)]
      (let [f (format "%s/%s-%s.json"
                      bulk-data-dir
                      prefix
                      (let [now (ZonedDateTime/now)]
                        (str (.toLocalDate now)
                             "-"
                             (format "%02d%02d%02d"
                                     (.getHour now)
                                     (.getMinute now)
                                     (.getSecond now)))))
            cards (cond->> cards
                    filter-fn
                    (filter filter-fn))]
        (io/make-parents f)
        (with-open [w (io/writer f :append true)]
          (.write w "[\n")
          (doseq [{{:keys [id]} :data :as card} cards]
            (.write w (representation/render-item
                       card
                       {:request {:uri id}
                        :representation
                        {:media-type "application/vnd.api+json"}}))
            (when (not= card (last cards))
              (.write w ",\n")))
          (.write w "\n]"))))))
