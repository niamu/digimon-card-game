(ns dcg.api.resources.release
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.db :as db]
   [dcg.api.resources.errors :as errors]
   [dcg.api.routes :as-alias routes]
   [dcg.api.router :as router]
   [dcg.api.utils :as utils]))

(liberator/defresource release-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :etag (fn [{::keys [release]}] (utils/sha release))
   :exists? (fn [{{{:keys [language release-id]} :path-params} :request}]
              (when-let [release (db/q '{:find [(pull ?r [:release/id
                                                          :release/name
                                                          :release/genre
                                                          :release/product-uri
                                                          :release/cardlist-uri
                                                          {:release/image
                                                           [:image/path]}
                                                          {:release/thumbnail
                                                           [:image/path]}
                                                          :release/date
                                                          {:card/_releases
                                                           [:card/number
                                                            :card/parallel-id]}]) .]
                                         :in [$ ?language ?release]
                                         :where [[?r :release/name ?name]
                                                 [?r :release/language ?language]
                                                 [(dcg.api.utils/slugify ?name) ?slug]
                                                 [(= ?slug ?release)]
                                                 [?c :card/releases ?r]
                                                 [?c :card/language ?l]
                                                 [?c :card/image ?i]
                                                 [?c :card/number ?number]
                                                 [?i :image/language ?l]]}
                                       language
                                       (string/lower-case release-id))]
                {::release (dissoc release
                                   :release/id
                                   :card/_releases)
                 ::cards (->> release
                              :card/_releases
                              (map (juxt :card/number
                                         :card/parallel-id))
                              sort)}))
   :existed? (fn [{{{:keys [language release-id]} :path-params} :request}]
               (and (s/conform ::routes/language language)
                    (not= release-id
                          (s/conform ::routes/release-id release-id))))
   :moved-temporarily? true
   :location (fn [{{{:keys [language release-id]} :path-params} :request}]
               (router/by-name ::routes/release
                               {:path
                                {:language (s/conform ::routes/language language)
                                 :release-id (string/lower-case release-id)}}))
   :handle-ok
   (fn [{{{:keys [language release-id]} :path-params} :request
        ::keys [cards]
        release ::release}]
     (cond-> {:data
              {:type "release"
               :id (router/by-name ::routes/release
                                   {:path
                                    {:language language
                                     :release-id release-id}})
               :attributes
               (cond-> release
                 (:release/date release)
                 (update :release/date utils/inst->iso8601)
                 (:release/image release)
                 (update :release/image :image/path)
                 (:release/thumbnail release)
                 (update :release/thumbnail :image/path))}}
       (seq cards)
       (-> (assoc-in [:data :relationships :cards]
                     {:data
                      (->> cards
                           (map (fn [[number p]]
                                  {:type "card"
                                   :id (router/by-name
                                        ::routes/card
                                        {:path
                                         {:language language
                                          :card-id (str number
                                                        (when-not (zero? p)
                                                          (str "_P" p)))}})})))})
           (update :included
                   (fnil concat [])
                   (->> cards
                        (map (fn [[number p]]
                               {:type "card"
                                :id (router/by-name
                                     ::routes/card
                                     {:path
                                      {:language language
                                       :card-id (str number
                                                     (when-not (zero? p)
                                                       (str "_P" p)))}})
                                :links
                                {:self
                                 (->> (router/by-name
                                       ::routes/card
                                       {:path
                                        {:language language
                                         :card-id (str number
                                                       (when-not (zero? p)
                                                         (str "_P" p)))}})
                                      utils/update-api-path)}})))))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :handle-not-found errors/error404-body
   :as-response (fn [data context]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})

(liberator/defresource language-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :exists? (fn [{{{:keys [language]} :path-params} :request}]
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
                {::db-query db-query}))
   :existed? (fn [{{{:keys [language]} :path-params} :request}]
               (s/conform ::routes/language language))
   :moved-temporarily? true
   :location (fn [{{{:keys [language]} :path-params} :request}]
               (router/by-name ::routes/releases-for-language
                               {:path
                                {:language (s/conform ::routes/language language)}}))
   :etag (fn [{::keys [db-query]}]
           (utils/sha db-query))
   :handle-ok
   (fn [{{{:keys [language]} :path-params} :request
         ::keys [db-query]}]
     (cond-> {:data
              {:type "language"
               :id (router/by-name ::routes/releases-for-language
                                   {:path
                                    {:language language}})
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
                           (map (fn [{n :release/name}]
                                  {:type "release"
                                   :id (router/by-name
                                        ::routes/release
                                        {:path
                                         {:language language
                                          :release-id (utils/slugify n)}})})))})
           (update :included
                   (fnil concat [])
                   (->> db-query
                        (map (fn [[count {:release/keys [name]
                                          :as release}]]
                               [count
                                (assoc release
                                       :release/id (utils/slugify name))]))
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
                               {:type "release"
                                :id (router/by-name
                                     ::routes/release
                                     {:path
                                      {:language language
                                       :release-id (:release/id r)}})
                                :links
                                {:self (->> (router/by-name
                                             ::routes/release
                                             {:path
                                              {:language language
                                               :release-id (:release/id r)}})
                                            utils/update-api-path)}
                                :meta
                                (cond-> {:name (:release/name r)
                                         :cards (:release/cards r)}
                                  (:release/date r)
                                  (assoc :date
                                         (:release/date r)))})))))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :handle-not-found errors/error404-body
   :as-response (fn [data context]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})

(liberator/defresource releases-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :exists? (fn [_]
              {::db-query (db/q '{:find [?l (max ?date) (count ?r)]
                                  :where [[?c :card/releases ?r]
                                          [?r :release/date ?date]
                                          [?c :card/language ?l]
                                          [?i :image/language ?l]]})})
   :etag (fn [{::keys [db-query]}] (utils/sha db-query))
   :handle-ok
   (fn [{::keys [db-query]}]
     (->> db-query
          (sort-by (comp inst-ms second) >)
          (mapv (fn [[language last-release releases]]
                  (let [route (router/by-name ::routes/releases-for-language
                                              {:path
                                               {:language language}})]
                    {:type "language"
                     :id route
                     :meta {:latest-release (utils/inst->iso8601 last-release)
                            :total-releases releases}
                     :links {:self (utils/update-api-path route)}})))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :as-response (fn [data context]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})
