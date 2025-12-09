(ns dcg.api.resources.index
  (:require
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.resources.errors :as errors]))

(liberator/defresource index-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :malformed? true
   :handle-malformed errors/error400-body
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :as-response (fn [data context]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})
