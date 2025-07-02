(ns dcg.api.routes
  (:require
   [dcg.api.resources.bulk-data :refer [bulk-data-resource]]
   [dcg.api.resources.card :refer [card-resource]]
   [dcg.api.resources.index :refer [index-resource]]
   [dcg.api.resources.release :refer [releases-resource release-resource]]))

(def routes
  [["/languages"
    {:name ::languages
     :handler #'index-resource}]
   ["/languages/:language"
    {:name ::language
     :conflicting true
     :handler (fn [request]
                ((#'releases-resource request) request))}]
   ["/:language/releases/:release"
    {:name ::release
     :handler (fn [request]
                ((#'release-resource request) request))}]
   ["/:language/cards/:card-id"
    {:name ::card
     :handler (fn [request]
                ((#'card-resource request) request))}]
   ["/bulk-data"
    {:name ::bulk-data
     :handler (fn [request]
                ((#'bulk-data-resource request) request))}]])
