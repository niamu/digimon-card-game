(ns dcg.api.resources.release
  (:require
   [clojure.string :as string]
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.db :as db]
   [dcg.api.resources.errors :as errors]
   [dcg.api.routes :as-alias routes]
   [dcg.api.utils :as utils]))

(liberator/defresource release-resource
  [{{:keys [language release]} :path-params}]
  (let [db-query (db/q '{:find [(pull ?r [:release/name
                                          :release/genre
                                          :release/product-uri
                                          :release/cardlist-uri
                                          {:release/image
                                           [:image/path]}
                                          {:release/thumbnail
                                           [:image/path]}
                                          :release/date]) .]
                         :in [$ ?language ?release]
                         :where [[?r :release/id ?id]
                                 [?r :release/language ?language]
                                 [(dcg.api.utils/short-uuid ?id) ?rid]
                                 [(= ?rid ?release)]
                                 [?c :card/releases ?r]
                                 [?c :card/language ?l]
                                 [?c :card/image ?i]
                                 [?c :card/number ?number]
                                 [?i :image/language ?l]]}
                       language
                       release)]
    {:allowed-methods [:head :get]
     :available-media-types ["application/vnd.api+json"]
     :etag (fn [_] (utils/sha db-query))
     :exists? (fn [_] (boolean db-query))
     :handle-ok
     (fn [{{{:keys [language release]} :path-params
           :as request} :request}]
       (let [cards (->> (db/q '{:find [?number ?p]
                                :in [$ ?language ?release]
                                :where [[?r :release/id ?id]
                                        [?r :release/language ?language]
                                        [(dcg.api.utils/short-uuid ?id) ?rid]
                                        [(= ?rid ?release)]
                                        [?c :card/releases ?r]
                                        [?c :card/language ?l]
                                        [?c :card/image ?i]
                                        [?c :card/number ?number]
                                        [?c :card/parallel-id ?p]
                                        [?i :image/language ?l]]}
                              language
                              release)
                        sort)]
         (cond-> {:data
                  {:type "releases"
                   :id (utils/route-by-name request
                                            ::routes/release
                                            {:language language
                                             :release release})
                   :attributes
                   (cond-> db-query
                     (:release/image db-query)
                     (update-in [:release/image]
                                (fn [{path :image/path}]
                                  (utils/update-image-path request path)))
                     (:release/thumbnail db-query)
                     (update-in [:release/thumbnail]
                                (fn [{path :image/path}]
                                  (utils/update-image-path request path)))
                     (:release/date db-query)
                     (update :release/date utils/inst->iso8601))}}
           (seq cards)
           (-> (assoc-in [:data :relationships :cards]
                         {:data
                          (->> cards
                               (map (fn [[number p]]
                                      {:type "cards"
                                       :id (utils/route-by-name
                                            request
                                            ::routes/card
                                            {:language language
                                             :card-id (str number
                                                           (when-not (zero? p)
                                                             (str "_P" p)))})})))})
               (update :include
                       (fnil concat [])
                       (->> cards
                            (map (fn [[number p]]
                                   {:type "cards"
                                    :id (utils/route-by-name
                                         request
                                         ::routes/card
                                         {:language language
                                          :card-id (str number
                                                        (when-not (zero? p)
                                                          (str "_P" p)))})
                                    :links
                                    {:self
                                     (->> (utils/route-by-name
                                           request
                                           ::routes/card
                                           {:language language
                                            :card-id (str number
                                                          (when-not (zero? p)
                                                            (str "_P" p)))})
                                          (utils/update-api-path request))}}))))))))
     :handle-method-not-allowed errors/error405-body
     :handle-not-acceptable errors/error406-body
     :handle-not-found errors/error404-body
     :as-response (fn [data {representation :representation :as context}]
                    (-> data
                        (representation/as-response
                         (assoc-in context
                                   [:representation :media-type]
                                   "application/vnd.api+json"))))}))

(liberator/defresource releases-resource
  [{{:keys [language]} :path-params
    :as request}]
  (let [db-query (db/q '{:find [(count-distinct ?card-id)
                                (pull ?r [:release/id
                                          :release/name
                                          :release/date])]
                         :in [$ ?language]
                         :where [[?r :release/language ?language]
                                 [?c :card/releases ?r]
                                 [?c :card/language ?l]
                                 [?c :card/image ?i]
                                 [?c :card/id ?card-id]
                                 [?i :image/language ?l]]}
                       language)]
    {:allowed-methods [:head :get]
     :available-media-types ["application/vnd.api+json"]
     :etag (fn [_] (utils/sha db-query))
     :exists? (fn [_] (boolean db-query))
     :handle-ok
     (fn [_]
       (cond-> {:data
                {:type "languages"
                 :id (utils/route-by-name
                      request
                      ::routes/language
                      {:language language})
                 :attributes {:code language
                              :name (case language
                                      "ja" "Japanese"
                                      "en" "English"
                                      "zh-Hans" "Chinese (Simplified)"
                                      "ko" "Korean")}}}
         (seq db-query)
         (-> (assoc-in [:data :relationships :releases]
                       {:data
                        (->> db-query
                             (map (fn [[count {:release/keys [name]
                                              :as release}]]
                                    [count
                                     (update release
                                             :release/id utils/short-uuid)]))
                             (sort-by (comp (juxt :release/date
                                                  :release/id) second))
                             (mapv (fn [[card-count r]]
                                     (let [m (reduce-kv (fn [m k v]
                                                          (if (nil? v)
                                                            m
                                                            (assoc m k v)))
                                                        {}
                                                        r)]
                                       (cond-> (assoc m
                                                      :release/cards
                                                      card-count)
                                         (:release/date m)
                                         (update :release/date utils/inst->iso8601)))))
                             (map (fn [r]
                                    {:type "releases"
                                     :id (utils/route-by-name
                                          request
                                          ::routes/release
                                          {:language language
                                           :release (:release/id r)})})))})
             (update :included
                     (fnil concat [])
                     (->> db-query
                          (map (fn [[count {:release/keys [name]
                                           :as release}]]
                                 [count
                                  (update release
                                          :release/id utils/short-uuid)]))
                          (sort-by (comp (juxt :release/date
                                               :release/id) second))
                          (mapv (fn [[card-count r]]
                                  (let [m (reduce-kv (fn [m k v]
                                                       (if (nil? v)
                                                         m
                                                         (assoc m k v)))
                                                     {}
                                                     r)]
                                    (cond-> (assoc m
                                                   :release/cards
                                                   card-count)
                                      (:release/date m)
                                      (update :release/date utils/inst->iso8601)))))
                          (map (fn [r]
                                 {:type "releases"
                                  :id (utils/route-by-name
                                       request
                                       ::routes/release
                                       {:language language
                                        :release (:release/id r)})
                                  :links
                                  {:self (->> (utils/route-by-name
                                               request
                                               ::routes/release
                                               {:language language
                                                :release (:release/id r)})
                                              (utils/update-api-path request))}
                                  :meta
                                  (cond-> {:name (:release/name r)
                                           :cards (:release/cards r)}
                                    (:release/date r)
                                    (assoc :date
                                           (:release/date r)))})))))))
     :handle-method-not-allowed errors/error405-body
     :handle-not-acceptable errors/error406-body
     :handle-not-found errors/error404-body
     :as-response (fn [data {representation :representation :as context}]
                    (-> data
                        (representation/as-response
                         (assoc-in context
                                   [:representation :media-type]
                                   "application/vnd.api+json"))))}))
