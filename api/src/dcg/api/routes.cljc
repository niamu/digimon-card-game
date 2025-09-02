(ns dcg.api.routes
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::q string?)
(s/def ::page pos-int?)

(def routes
  [["/"
    {:name ::index
     :handler 'dcg.api.resources.index/index-resource}]
   ["/search"
    {:name ::search
     :handler 'dcg.api.resources.search/search-resource
     :parameters {:query (s/keys :opt-un [::q ::page])}}]
   ["/ja"
    {:name ::ja
     :handler 'dcg.api.resources.release/releases-resource}]
   ["/en"
    {:name ::en
     :handler 'dcg.api.resources.release/releases-resource}]
   ["/zh-Hans"
    {:name ::zh-Hans
     :handler 'dcg.api.resources.release/releases-resource}]
   ["/ko"
    {:name ::ko
     :handler 'dcg.api.resources.release/releases-resource}]
   ["/:language/releases/:release"
    {:name ::release
     :handler 'dcg.api.resources.release/release-resource}]
   ["/:language/cards/:card-id"
    {:name ::card
     :handler 'dcg.api.resources.card/card-resource}]
   ["/bulk-data"
    {:name ::bulk-data
     :handler 'dcg.api.resources.bulk-data/bulk-data-resource}]])
