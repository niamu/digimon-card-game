(ns dcg.api.resources.release
  (:require
   [liberator.core :as liberator]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.db.db :as db]))

(defn release-resource
  [{{:keys [language release]} :path-params :as request}]
  ((liberator/resource
    {:allowed-methods [:get]
     :available-media-types ["application/vnd.api+json"]
     :exists? (fn [{{{:keys [language release]} :path-params} :request}]
                (boolean
                 (->> (db/q '{:find [?r]
                              :in [$ ?language ?release]
                              :where [[?c :card/releases ?r]
                                      [?c :card/language ?l]
                                      [?c :card/image ?i]
                                      [?c :card/number ?number]
                                      [?i :image/language ?l]
                                      [?r :release/language ?language]
                                      [?r :release/name ?release-name]
                                      [(.contains ?release-name ?release)]]}
                            language
                            release)
                      ffirst)))
     :handle-ok
     (fn [{{{:keys [language release]} :path-params} :request
          :as context}]
       {:data {:id (str language "_" release)
               :type "release"
               :attributes
               (->> (db/q '{:find [(pull ?r [:release/name
                                             :release/date])]
                            :in [$ ?language ?release]
                            :where [[?c :card/releases ?r]
                                    [?c :card/language ?l]
                                    [?c :card/image ?i]
                                    [?c :card/number ?number]
                                    [?i :image/language ?l]
                                    [?r :release/language ?language]
                                    [?r :release/name ?release-name]
                                    [(.contains ?release-name ?release)]]}
                          language
                          release)
                    ffirst)
               :relationships
               {:cards
                {:data
                 (->> (db/q '{:find [?number ?p]
                              :in [$ ?language ?release]
                              :where [[?c :card/releases ?r]
                                      [?c :card/language ?l]
                                      [?c :card/image ?i]
                                      [?c :card/number ?number]
                                      [?c :card/parallel-id ?p]
                                      [?i :image/language ?l]
                                      [?r :release/language ?language]
                                      [?r :release/name ?release-name]
                                      [(.contains ?release-name ?release)]]}
                            language
                            release)
                      sort
                      (map (fn [[number p]]
                             {:data
                              {:id (str language
                                        "_"
                                        number
                                        (when-not (zero? p)
                                          (str "_P" p)))
                               :type "card"}
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
                                   "application/vnd.api+json"))))})
   request))
