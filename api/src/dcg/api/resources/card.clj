(ns dcg.api.resources.card
  (:require
   [clojure.string :as string]
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.db.db :as db]))

(defn card-resource
  [{{:keys [language card-id]} :path-params :as request}]
  (let [[number parallel-id] (string/split card-id #"_")
        parallel-id (or (some-> parallel-id
                                (subs 1)
                                parse-long)
                        0)
        card (some-> (->> (db/q '{:find [(pull ?c [:card/name
                                                   :card/number
                                                   :card/parallel-id
                                                   :card/level
                                                   :card/effect
                                                   :card/inherited-effect
                                                   :card/security-effect
                                                   :card/dp
                                                   :card/rarity
                                                   {:card/releases
                                                    [:release/id
                                                     :release/name
                                                     :release/date
                                                     :release/genre]}
                                                   :card/block-icon
                                                   :card/category
                                                   :card/attribute
                                                   :card/form
                                                   :card/type
                                                   {:card/color [:color/color]}
                                                   {:card/digivolution-requirements
                                                    [:digivolve/color
                                                     :digivolve/level
                                                     :digivolve/cost]}
                                                   {:card/image [:image/path]}
                                                   :card/notes])]
                                  :in [$ ?language ?number ?parallel-id]
                                  :where [[?c :card/language ?language]
                                          [?c :card/image ?i]
                                          [?c :card/number ?number]
                                          [?c :card/parallel-id ?parallel-id]
                                          [?i :image/language ?language]]}
                                language
                                number
                                parallel-id)
                          ffirst)
                     (update :card/color
                             #(map :color/color %))
                     (update :card/image
                             (fn [{path :image/path}]
                               (str (utils/base-url {:request request})
                                    path))))
        alt-arts (->> (db/q '{:find [(pull ?c [:card/notes
                                               :card/number
                                               :card/parallel-id])]
                              :in [$ ?language ?number ?parallel-id]
                              :where [[?c :card/language ?language]
                                      [?c :card/image ?i]
                                      [?c :card/number ?number]
                                      (not [?c :card/parallel-id ?parallel-id])
                                      [?i :image/language ?language]]}
                            language
                            number
                            parallel-id)
                      (apply concat))]
    ((liberator/resource
      {:allowed-methods [:get]
       :available-media-types ["application/vnd.api+json"]
       :etag (fn [context] (utils/sha card))
       :exists? (fn [_] (boolean card))
       :handle-ok
       (fn [{{{:keys [_ _]} :path-params} :request
            :as context}]
         {:data
          (cond-> {:id (str language "_"
                            number
                            (when-not (zero? parallel-id)
                              (str "_P" parallel-id)))
                   :type "card"
                   :attributes (dissoc card :card/releases)}
            (seq alt-arts)
            (assoc-in [:relationships :alternate-arts]
                      {:data
                       (->> alt-arts
                            (map (fn [{:card/keys [notes number parallel-id]}]
                                   {:type "card"
                                    :id (str language "_"
                                             number
                                             (when-not (zero? parallel-id)
                                               (str "_P" parallel-id)))
                                    :links
                                    {:related
                                     (cond-> {:href
                                              (format
                                               "%s/%s"
                                               (utils/base-url context)
                                               (str language
                                                    "/"
                                                    "cards"
                                                    "/"
                                                    number
                                                    (when-not (zero? parallel-id)
                                                      (str "_P" parallel-id))))}
                                       notes
                                       (assoc :meta
                                              {:notes notes}))}})))})
            (seq (:card/releases card))
            (assoc-in [:relationships :releases]
                      {:data
                       (->> (:card/releases card)
                            (map (fn [{:release/keys [name]
                                      :as release}]
                                   (assoc release
                                          :release/id
                                          (->> name
                                               (re-find #"[【\[](.*)[】\]]")
                                               second))))
                            (sort-by (juxt :release/date
                                           :release/id))
                            (mapv (fn [r]
                                    (let [m (reduce-kv (fn [m k v]
                                                         (if (nil? v)
                                                           m
                                                           (assoc m k v)))
                                                       {}
                                                       r)]
                                      (cond-> m
                                        (:release/date m)
                                        (update :release/date str)))))
                            (map (fn [r]
                                   {:id (str language "_"
                                             (:release/id r))
                                    :type "release"
                                    :meta {:name (:release/name r)}
                                    :links
                                    {:related (format "%s/%s"
                                                      (utils/base-url context)
                                                      (str language
                                                           "/releases/"
                                                           (:release/id r)))}})))}))})
       :handle-method-not-allowed errors/error405-body
       :handle-not-acceptable errors/error406-body
       :handle-not-found errors/error404-body
       :as-response (fn [data {representation :representation :as context}]
                      (-> data
                          (representation/as-response
                           (assoc-in context
                                     [:representation :media-type]
                                     "application/vnd.api+json"))))})
     request)))
