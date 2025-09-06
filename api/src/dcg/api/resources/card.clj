(ns dcg.api.resources.card
  (:require
   [clojure.string :as string]
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.api.db :as db]
   [dcg.api.routes :as-alias routes]
   [dcg.api.router :as router]))

(def query
  [:card/id
   :card/name
   :card/number
   :card/category
   :card/parallel-id
   :card/level
   :card/dp
   :card/play-cost
   :card/use-cost
   :card/language
   :card/form
   :card/attribute
   :card/type
   :card/rarity
   {:card/supplemental-rarity
    [:supplemental-rarity/stars
     :supplemental-rarity/stamp]}
   :card/block-icon
   :card/notes
   {:card/color [:color/index
                 :color/color]}
   {:card/digivolution-requirements [:digivolve/index
                                     :digivolve/level
                                     :digivolve/category
                                     :digivolve/form
                                     :digivolve/color
                                     :digivolve/cost]}
   {:card/releases [:release/id
                    :release/name
                    :release/genre
                    :release/date
                    :release/product-uri
                    {:release/thumbnail [:image/path]}
                    {:release/image [:image/path]}]}
   {:card/image [:image/path]}
   {:card/highlights [:highlight/index
                      :highlight/field
                      :highlight/type
                      :highlight/text]}
   :card/effect
   :card/inherited-effect
   :card/security-effect
   {:card/errata [:errata/date
                  :errata/error
                  :errata/correction
                  :errata/notes]}
   {:card/limitations [:limitation/date
                       :limitation/type
                       :limitation/allowance
                       :limitation/paired-card-numbers
                       :limitation/note]}
   {:card/faqs [:faq/id
                :faq/date
                :faq/question
                :faq/answer]}
   {:card/panorama [:panorama/columns
                    {:panorama/cards [:card/id
                                      :card/name
                                      :card/number
                                      :card/parallel-id
                                      :card/language
                                      {:card/image
                                       [:image/path]}]}
                    :panorama/order]}])

(defn process-card
  [{:card/keys [language number parallel-id]
    :as card} alt-arts international-arts]
  (let [card (some-> card
                     (dissoc :card/id)
                     (update :card/color
                             (fn [colors]
                               (->> colors
                                    (sort-by :color/index)
                                    (map :color/color))))
                     (update :card/digivolution-requirements
                             (fn [coll]
                               (some->> coll
                                        (sort-by :digivolve/index)
                                        (map #(dissoc % :digivolve/index)))))
                     (update :card/faqs
                             (fn [coll]
                               (->> coll
                                    (sort-by :faq/id)
                                    (map #(dissoc % :faq/id)))))
                     (update :card/image
                             (fn [{path :image/path}] path)))
        card (cond-> card
               (not (seq (:card/digivolution-requirements card)))
               (dissoc :card/digivolution-requirements)
               (not (seq (:card/faqs card)))
               (dissoc :card/faqs))]
    (cond-> {:data
             {:type "cards"
              :id (router/by-name
                   ::routes/card
                   {:path {:language language
                           :card-id
                           (str number
                                (when-not (and parallel-id
                                               (zero? parallel-id))
                                  (str "_P" parallel-id)))}})
              :attributes (cond-> (dissoc card
                                          :card/releases
                                          :card/highlights)
                            (:card/errata card)
                            (update :card/errata
                                    (fn [errata]
                                      (cond-> errata
                                        (:errata/date errata)
                                        (update :errata/date
                                                utils/inst->iso8601))))
                            (:card/faqs card)
                            (update :card/faqs
                                    (fn [faqs]
                                      (map (fn [faq]
                                             (cond-> faq
                                               (:faq/date faq)
                                               (update :faq/date
                                                       utils/inst->iso8601)))
                                           faqs)))
                            (:card/limitations card)
                            (update :card/limitations
                                    (fn [limitations]
                                      (map (fn [l]
                                             (cond-> l
                                               (:limitation/date l)
                                               (update :limitation/date
                                                       utils/inst->iso8601)))
                                           limitations))))}}
      (seq alt-arts)
      (-> (assoc-in [:data :relationships :alternate-arts]
                    {:data
                     (->> alt-arts
                          (map (fn [{:card/keys [notes number parallel-id]}]
                                 {:type "cards"
                                  :id (router/by-name
                                       ::routes/card
                                       {:path
                                        {:language language
                                         :card-id
                                         (str number
                                              (when-not (zero? parallel-id)
                                                (str "_P" parallel-id)))}})})))})
          (update :included
                  (fnil concat [])
                  (->> alt-arts
                       (map (fn [{:card/keys [notes number parallel-id]}]
                              (cond-> {:type "cards"
                                       :id (router/by-name
                                            ::routes/card
                                            {:path
                                             {:language language
                                              :card-id
                                              (str number
                                                   (when-not (zero? parallel-id)
                                                     (str "_P" parallel-id)))}})
                                       :links
                                       {:self (->> (router/by-name
                                                    ::routes/card
                                                    {:path
                                                     {:language language
                                                      :card-id
                                                      (cond-> number
                                                        (pos? parallel-id)
                                                        (str "_P" parallel-id))}})
                                                   utils/update-api-path)}}
                                notes
                                (assoc :meta
                                       {:notes notes})))))))
      (seq international-arts)
      (-> (assoc-in [:data :relationships :international-arts]
                    {:data
                     (->> international-arts
                          (map (fn [{:card/keys [notes number language parallel-id]}]
                                 {:type "cards"
                                  :id (router/by-name
                                       ::routes/card
                                       {:path
                                        {:language language
                                         :card-id
                                         (str number
                                              (when-not (zero? parallel-id)
                                                (str "_P" parallel-id)))}})})))})
          (update :included
                  (fnil concat [])
                  (->> international-arts
                       (map (fn [{:card/keys [notes number language parallel-id]}]
                              (cond-> {:type "cards"
                                       :id (router/by-name
                                            ::routes/card
                                            {:path
                                             {:language language
                                              :card-id
                                              (str number
                                                   (when-not (zero? parallel-id)
                                                     (str "_P" parallel-id)))}})
                                       :links
                                       {:self (->> (router/by-name
                                                    ::routes/card
                                                    {:path
                                                     {:language language
                                                      :card-id
                                                      (str number
                                                           (when-not (zero? parallel-id)
                                                             (str "_P" parallel-id)))}})
                                                   utils/update-api-path)}}
                                notes
                                (assoc :meta
                                       {:notes notes})))))))
      (seq (:card/releases card))
      (-> (assoc-in [:data :relationships :releases]
                    {:data
                     (->> (:card/releases card)
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
                                      (update :release/date
                                              utils/inst->iso8601)))))
                          (map (fn [{:release/keys [name] :as r}]
                                 {:type "releases"
                                  :id (router/by-name
                                       ::routes/release
                                       {:path
                                        {:language language
                                         :release (utils/slugify name)}})})))})
          (update :included
                  (fnil concat [])
                  (->> (:card/releases card)
                       (map (fn [{:release/keys [id name date]}]
                              {:type "releases"
                               :id (router/by-name
                                    ::routes/release
                                    {:path
                                     {:language language
                                      :release (utils/slugify name)}})
                               :links
                               {:self
                                (->> (router/by-name
                                      ::routes/release
                                      {:path
                                       {:language language
                                        :release (utils/slugify name)}})
                                     utils/update-api-path)}
                               :meta {:name name}}))))))))

(defn all-cards
  []
  (->> (db/q {:find [[(list 'pull '?c query) '...]]
              :where '[[?c :card/id _]]})
       (map (fn [{:card/keys [language number parallel-id] :as card}]
              (let [alt-arts (db/q '{:find [[(pull ?c [:card/notes
                                                       :card/number
                                                       :card/parallel-id]) ...]]
                                     :in [$ ?language ?number ?p]
                                     :where [[?c :card/language ?language]
                                             [?c :card/number ?number]
                                             (not [?c :card/parallel-id ?p])]}
                                   language
                                   number
                                   parallel-id)
                    international-arts
                    (db/q '{:find [[(pull ?c [:card/notes
                                              :card/language
                                              :card/number
                                              :card/parallel-id]) ...]]
                            :in [$ ?language ?number]
                            :where [[?c :card/number ?number]
                                    (not [?c :card/language ?language])]}
                          language
                          number)]
                (process-card card alt-arts international-arts))))))

(liberator/defresource card-resource
  {:allowed-methods [:head :get]
   :available-media-types ["application/vnd.api+json"]
   :exists?
   (fn [{{{:keys [language card-id]} :path-params} :request}]
     (let [[number parallel-id] (string/split card-id #"_P")
           parallel-id (or (some-> parallel-id
                                   parse-long)
                           0)
           card (db/q {:find [(list 'pull '?c query) '.]
                       :in '[$ ?language ?number ?parallel-id]
                       :where '[[?c :card/language ?language]
                                [?c :card/image ?i]
                                [?c :card/number ?number]
                                [?c :card/parallel-id ?parallel-id]
                                [?i :image/language ?language]]}
                      language
                      number
                      parallel-id)
           international-arts (db/q '{:find [[(pull ?c [:card/notes
                                                        :card/language
                                                        :card/number
                                                        :card/parallel-id]) ...]]
                                      :in [$ ?language ?number]
                                      :where [(not [?c :card/language ?language])
                                              [?c :card/image ?i]
                                              [?c :card/number ?number]
                                              (not [?i :image/language ?language])]}
                                    language
                                    number)
           alt-arts (db/q '{:find [[(pull ?c [:card/notes
                                              :card/number
                                              :card/parallel-id]) ...]]
                            :in [$ ?language ?number ?parallel-id]
                            :where [[?c :card/language ?language]
                                    [?c :card/image ?i]
                                    [?c :card/number ?number]
                                    (not [?c :card/parallel-id ?parallel-id])
                                    [?i :image/language ?language]]}
                          language
                          number
                          parallel-id)]
       (when card
         {::alt-arts alt-arts
          ::international-arts international-arts
          ::card card})))
   :etag (fn [{{media-type :media-type} :representation
              ::keys [card]}]
           (str (utils/sha card)
                "--"
                media-type))
   :handle-ok
   (fn [{{:card/keys [language number parallel-id] :as card} ::card
        ::keys [alt-arts international-arts]}]
     (process-card card alt-arts international-arts))
   :handle-method-not-allowed errors/error405-body
   :handle-not-acceptable errors/error406-body
   :handle-not-found errors/error404-body
   :as-response (fn [data {representation :representation :as context}]
                  (-> data
                      (representation/as-response
                       (assoc-in context
                                 [:representation :media-type]
                                 "application/vnd.api+json"))))})
