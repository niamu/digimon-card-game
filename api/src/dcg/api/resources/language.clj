(ns dcg.api.resources.language
  (:require
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.db.db :as db]))

(defn language-resource
  [{{:keys [language]} :path-params :as request}]
  ((liberator/resource
    (let [db-query (db/q '{:find [(count-distinct ?number)
                                  (pull ?r [:release/id
                                            :release/name
                                            :release/date])]
                           :in [$ ?language]
                           :where [[?c :card/releases ?r]
                                   [?c :card/language ?l]
                                   [?c :card/image ?i]
                                   [?c :card/number ?number]
                                   [?i :image/language ?l]
                                   [?r :release/language ?language]]}
                         language)]
      {:allowed-methods [:get]
       :available-media-types ["application/vnd.api+json"]
       :etag (fn [context] (utils/sha db-query))
       :exists? (fn [{{{:keys [language]} :path-params} :request}]
                  (boolean
                   (db/q '{:find [?r .]
                           :in [$ ?language]
                           :where [[?c :card/releases ?r]
                                   [?c :card/language ?l]
                                   [?c :card/image ?i]
                                   [?c :card/number ?number]
                                   [?i :image/language ?l]
                                   [?r :release/language ?language]]}
                         language)))
       :handle-ok
       (fn [{{{:keys [language]} :path-params} :request :as context}]
         {:data {:id language
                 :type "language"
                 :attributes {:tag language
                              :name (case language
                                      "ja" "Japanese"
                                      "en" "English"
                                      "zh-Hans" "Chinese"
                                      "ko" "Korean")}
                 :relationships
                 {:releases
                  {:data
                   (->> db-query
                        (map (fn [[count {:release/keys [name]
                                         :as release}]]
                               [count
                                (assoc release
                                       :release/id
                                       (->> name
                                            (re-find #"[【\[](.*)[】\]]")
                                            second))]))
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
                                    (update :release/date str)))))
                        (map (fn [r]
                               {:id (str language "_"
                                         (:release/id r))
                                :type "release"
                                :meta
                                {:name (:release/name r)
                                 :cards (:release/cards r)}
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
   request))
