(ns dcg.api.resources.errors
  (:require
   [clojure.data.json :as json]
   [liberator.representation :as representation]
   [dcg.api.utils :as utils]))

(defmethod representation/render-map-generic "application/vnd.api+json"
  [data {{:keys [uri] :as request} :request :as context}]
  (let [self-url (utils/update-api-path uri)]
    (json/write-str (cond-> data
                      (:data data)
                      (assoc-in [:links :self] self-url)))))

(defmethod representation/render-seq-generic "application/vnd.api+json"
  [data {{:keys [uri] :as request} :request :as context}]
  (let [self-url (utils/update-api-path uri)]
    (json/write-str (cond-> {:data data}
                      (nil? (:errors data))
                      (assoc-in [:links :self] self-url)))))

(def error400-body
  {:errors [{:status "400" :detail "Bad Request"}]
   :meta {:documentation
          {:description (format "This is the %s API. For documentation please see %s"
                                (or (System/getenv "API_NAME")
                                    "Digimon Card Game (2020)")
                                (str (System/getenv "SITE_ORIGIN")
                                     "/docs/api"))
           :url (str (System/getenv "SITE_ORIGIN")
                     "/docs/api")}}})

(def error400
  (constantly {:status 404
               :headers {"Content-Type" "application/vnd.api+json"}
               :body (representation/render-map-generic
                      error400-body
                      {:representation
                       {:media-type "application/vnd.api+json"}})}))

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
