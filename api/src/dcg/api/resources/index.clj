(ns dcg.api.resources.index
  (:require
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.db.db :as db]))

(liberator/defresource index-resource
  (let [db-query (db/q '{:find [?l (max ?date)]
                         :where [[?c :card/releases ?r]
                                 [?r :release/date ?date]
                                 [?c :card/language ?l]
                                 [?i :image/language ?l]]})]
    {:allowed-methods [:get]
     :available-media-types ["application/vnd.api+json"]
     :handle-ok (fn [context]
                  (->> db-query
                       (sort-by (comp inst-ms second) >)
                       (mapv (fn [[language last-release]]
                               {:id language
                                :type "language"
                                :attributes {:tag language
                                             :name (case language
                                                     "ja" "Japanese"
                                                     "en" "English"
                                                     "zh-Hans" "Chinese"
                                                     "ko" "Korean")}
                                :meta {:latest-release last-release}
                                :links {:self (format "%s/%s"
                                                      (utils/base-url context)
                                                      language)}}))))
     :etag (fn [context] (utils/sha db-query))
     :handle-method-not-allowed errors/error405-body
     :handle-not-acceptable errors/error406-body
     :as-response (fn [data {representation :representation :as context}]
                    (-> data
                        (representation/as-response
                         (assoc-in context
                                   [:representation :media-type]
                                   "application/vnd.api+json"))))}))
