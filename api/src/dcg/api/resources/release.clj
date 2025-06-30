(ns dcg.api.resources.release
  (:require
   [clojure.string :as string]
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.api.db :as db]))

(liberator/defresource release-resource
  [{{:keys [language release]} :path-params :as request}]
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
     :etag (fn [context] (utils/sha db-query))
     :exists? (fn [_] (boolean db-query))
     :handle-ok
     (fn [{{{:keys [language release]} :path-params
           :as request} :request
          :as context}]
       {:data {:id (str "/" language "/releases/" release)
               :type "releases"
               :attributes
               (cond-> db-query
                 (:release/image db-query)
                 (update-in [:release/image
                             :image/path]
                            (partial utils/update-image-path
                                     request))
                 (:release/thumbnail db-query)
                 (update-in [:release/thumbnail
                             :image/path]
                            (partial utils/update-image-path
                                     request))
                 (:release/date db-query)
                 (update :release/date utils/inst->iso8601))
               :relationships
               {:cards
                {:data
                 (->> (db/q '{:find [?number ?p]
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
                      sort
                      (map (fn [[number p]]
                             {:data
                              {:id (str "/" language "/cards/"
                                        number
                                        (when-not (zero? p)
                                          (str "_P" p)))
                               :type "cards"}
                              :links
                              {:related
                               (format "%s/%s"
                                       (utils/base-url context)
                                       (str language
                                            "/"
                                            "cards"
                                            "/"
                                            number
                                            (when-not (zero? p)
                                              (str "_P" p))))}})))}}}})
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
  [{{:keys [language]} :path-params :as request}]
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
     :etag (fn [context] (utils/sha db-query))
     :exists? (fn [_] (boolean db-query))
     :handle-ok
     (fn [{{{:keys [language]} :path-params} :request :as context}]
       {:data {:id language
               :type "languages"
               :attributes {:code language
                            :name (case language
                                    "ja" "Japanese"
                                    "en" "English"
                                    "zh-Hans" "Chinese (Simplified)"
                                    "ko" "Korean")}
               :relationships
               {:releases
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
                             {:id (str "/" language "/releases/"
                                       (:release/id r))
                              :type "releases"
                              :meta
                              (cond-> {:name (:release/name r)
                                       :cards (:release/cards r)}
                                (:release/date r)
                                (assoc :date
                                       (:release/date r)))
                              :links
                              {:related (format "%s/%s"
                                                (utils/base-url context)
                                                (str language
                                                     "/releases/"
                                                     (:release/id r)))}})))}}}})
     :handle-method-not-allowed errors/error405-body
     :handle-not-acceptable errors/error406-body
     :handle-not-found errors/error404-body
     :as-response (fn [data {representation :representation :as context}]
                    (-> data
                        (representation/as-response
                         (assoc-in context
                                   [:representation :media-type]
                                   "application/vnd.api+json"))))}))
