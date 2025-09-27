(ns dcg.api.routes
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [dcg.api.utils :as utils]))

(s/def ::q string?)
(s/def ::page pos-int?)

(s/def ::card-id (s/conformer
                  (fn [s]
                    (when (string? s)
                      (string/upper-case s)))))
(s/def ::language (s/conformer
                   (fn [s]
                     (-> (reduce (fn [accl l]
                                   (assoc accl
                                          (string/lower-case l)
                                          l))
                                 {}
                                 #{"ja" "en" "zh-Hans" "ko"})
                         (get (string/lower-case s))))))
(s/def ::release-id (s/conformer
                     (fn [s]
                       (when (string? s)
                         (utils/slugify s)))))

(def routes
  [["/"
    {:name ::index
     :handler 'dcg.api.resources.index/index-resource}]
   ["/search"
    {:name ::search
     :handler 'dcg.api.resources.search/search-resource
     :parameters {:query (s/keys :opt-un [::q
                                          ::page])}}]
   ["/releases"
    [""
     {:name ::releases
      :handler 'dcg.api.resources.release/releases-resource}]
    ["/:language"
     {:name ::releases-for-language
      :handler 'dcg.api.resources.release/language-resource
      :parameters {:path (s/keys :req-un [::language])}}]
    ["/:language/:release-id"
     {:name ::release
      :handler 'dcg.api.resources.release/release-resource
      :parameters {:path (s/keys :req-un [::language
                                          ::release-id])}}]]
   ["/cards/:language/:card-id"
    {:name ::card
     :handler 'dcg.api.resources.card/card-resource
     :parameters {:path (s/keys :req-un [::language
                                         ::card-id])}}]
   ["/bulk-data"
    {:name ::bulk-data
     :handler 'dcg.api.resources.bulk-data/bulk-data-resource}]])
