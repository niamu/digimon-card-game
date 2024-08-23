(ns dcg.api.resources.errors
  (:require
   [dcg.api.utils :as utils]
   [liberator.representation :as representation]))

(def error403-body
  {:errors [{:status "403" :detail "Invalid anti-forgery token"}]})

(def error403
  (constantly {:status 403
               :headers {"Content-Type" "application/vnd.api+json"}
               :body (representation/render-map-generic
                      error403-body
                      {:representation
                       {:media-type "application/vnd.api+json"}})}))

(def error404-body
  {:errors [{:status "404" :detail "Not Found"}]})

(def error404
  (constantly {:status 404
               :headers {"Content-Type" "application/vnd.api+json"}
               :body (representation/render-map-generic
                      error404-body
                      {:representation
                       {:media-type "application/vnd.api+json"}})}))

(def error405-body
  {:errors [{:status "405" :detail "Method Not Allowed"}]})

(def error405
  (constantly {:status 405
               :headers {"Content-Type" "application/vnd.api+json"}
               :body (representation/render-map-generic
                      error405-body
                      {:representation
                       {:media-type "application/vnd.api+json"}})}))

(def error406-body
  {:errors [{:status "406" :detail "Not Acceptable"}]})

(def error406
  (constantly {:status 406
               :headers {"Content-Type" "application/vnd.api+json"}
               :body (representation/render-map-generic
                      error406-body
                      {:representation
                       {:media-type "application/vnd.api+json"}})}))
