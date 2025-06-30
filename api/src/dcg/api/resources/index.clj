(ns dcg.api.resources.index
  (:require
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.api.db :as db]))

(liberator/defresource index-resource
  (let [db-query (db/q '{:find [?l (max ?date)]
                         :where [[?c :card/releases ?r]
                                 [?r :release/date ?date]
                                 [?c :card/language ?l]
                                 [?i :image/language ?l]]})]
    {:allowed-methods [:head :get]
     :available-media-types ["application/vnd.api+json"]
     :etag (fn [context] (utils/sha db-query))
     :handle-ok (fn [context]
                  (->> db-query
                       (sort-by (comp inst-ms second) >)
                       (mapv (fn [[language last-release]]
                               {:id language
                                :type "language"
                                :attributes
                                {:code language
                                 :name (case language
                                         "ja" "Japanese"
                                         "en" "English"
                                         "zh-Hans" "Chinese (Simplified)"
                                         "ko" "Korean")}
                                :meta
                                {:latest-release (utils/inst->iso8601 last-release)}
                                :links
                                {:related (format "%s/%s/releases"
                                                  (utils/base-url context)
                                                  language)}}))))
     :handle-method-not-allowed errors/error405-body
     :handle-not-acceptable errors/error406-body
     :as-response (fn [data {representation :representation :as context}]
                    (-> data
                        (representation/as-response
                         (assoc-in context
                                   [:representation :media-type]
                                   "application/vnd.api+json"))))}))
