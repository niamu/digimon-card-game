(ns dcg.api.resources.release
  (:require
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
   :exists? (fn [{{{:keys [language release]} :path-params} :request}]
              (when-let [release (db/q '{:find [(pull ?r [:release/id
                                                          :release/name
                                                          :release/genre
                                                          :release/product-uri
                                                          :release/cardlist-uri
                                                          {:release/image
                                                           [:image/path]}
                                                          {:release/thumbnail
                                                           [:image/path]}
                                                          :release/date]) .]
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
                                       release)]
                {::release (dissoc release
                                   :release/id)
                 ::cards (->> (db/q '{:find [?number ?p]
                                      :in [$ ?id]
                                      :where [[?r :release/id ?id]
                                              [?c :card/releases ?r]
                                              [?c :card/language ?l]
                                              [?c :card/image ?i]
                                              [?c :card/number ?number]
                                              [?c :card/parallel-id ?p]
                                              [?i :image/language ?l]]}
                                    (:release/id release))
                              sort)}))
   :handle-ok
   (fn [{{{:keys [language] release-slug :release} :path-params} :request
        ::keys [cards]
        release ::release}]
     (cond-> {:data
              {:type "releases"
               :id (router/by-name ::routes/release
                                   {:path
                                    {:language language
                                     :release release-slug}})
               :attributes
               (cond-> release
                 (:release/image release)
                 (update-in [:release/image :image/path]
                            utils/update-image-path)
                 (:release/thumbnail release)
                 (update-in [:release/thumbnail :image/path]
                            utils/update-image-path)
                 (:release/date release)
                 (update :release/date utils/inst->iso8601))}}
       (seq cards)
       (-> (assoc-in [:data :relationships :cards]
                     {:data
                      (->> cards
                           (map (fn [[number p]]
                                  {:type "cards"
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
                               {:type "cards"
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
   :as-response (fn [data {representation :representation :as context}]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})

(liberator/defresource releases-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :exists? (fn [{{:keys [uri]} :request}]
              (let [language (subs uri 1)
                    db-query (db/q '{:find [(count-distinct ?card-id)
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
   :etag (fn [{::keys [db-query]}]
           (utils/sha db-query))
   :handle-ok
   (fn [{{:keys [uri]} :request
        ::keys [db-query]}]
     (let [language (subs uri 1)]
       (cond-> {:data
                {:type "languages"
                 :id (router/by-name (keyword "dcg.api.routes"
                                              language))
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
                             (map (fn [{n :release/name
                                       :as r}]
                                    {:type "releases"
                                     :id (router/by-name
                                          ::routes/release
                                          {:path
                                           {:language language
                                            :release (utils/slugify n)}})})))})
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
                                 {:type "releases"
                                  :id (router/by-name
                                       ::routes/release
                                       {:path
                                        {:language language
                                         :release (:release/id r)}})
                                  :links
                                  {:self (->> (router/by-name
                                               ::routes/release
                                               {:path
                                                {:language language
                                                 :release (:release/id r)}})
                                              utils/update-api-path)}
                                  :meta
                                  (cond-> {:name (:release/name r)
                                           :cards (:release/cards r)}
                                    (:release/date r)
                                    (assoc :date
                                           (:release/date r)))}))))))))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :handle-not-found errors/error404-body
   :as-response (fn [data {representation :representation :as context}]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})
