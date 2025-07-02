(ns dcg.api.resources.bulk-data
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.api.resources.errors :as errors]
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
  [request]
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :etag (fn [_] (->> (all-files)
                     (map :filename)
                     (string/join ",")
                     utils/sha))
   :handle-ok (fn [{request :request}]
                (->> (all-files)
                     (map (fn [{:keys [filename path size updated-at]}]
                            {:type "bulk-data"
                             :id (utils/sha filename)
                             :attributes
                             {:name (-> filename
                                        (string/split #"-")
                                        first
                                        (string/split #"_")
                                        (as-> #__ s
                                          (->> s
                                               (map string/capitalize)
                                               (string/join " "))))
                              :size size
                              :content-type "application/vnd.api+json"
                              :updated-at updated-at}
                             :links
                             {:download (->> path
                                             (utils/update-api-path request))}}))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :as-response (fn [data {representation :representation :as context}]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})
