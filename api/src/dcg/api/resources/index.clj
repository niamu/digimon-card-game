(ns dcg.api.resources.index
  (:require
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.router :as router]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.api.db :as db]))

(liberator/defresource index-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :exists? (fn [{request :request}]
              {::db-query (db/q '{:find [?l (max ?date)]
                                  :where [[?c :card/releases ?r]
                                          [?r :release/date ?date]
                                          [?c :card/language ?l]
                                          [?i :image/language ?l]]})})
   :etag (fn [{::keys [db-query]}] (utils/sha db-query))
   :handle-ok
   (fn [{request :request
        ::keys [db-query]}]
     (->> db-query
          (sort-by (comp inst-ms second) >)
          (mapv (fn [[language last-release]]
                  (let [route-name (keyword "dcg.api.routes"
                                            language)
                        route (router/by-name route-name)]
                    {:type "languages"
                     :id route
                     :meta {:latest-release (utils/inst->iso8601 last-release)}
                     :links {:self (utils/update-api-path route)}})))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :as-response (fn [data {representation :representation :as context}]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})
