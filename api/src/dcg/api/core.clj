(ns dcg.api.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults :as defaults]
   [taoensso.timbre :as logging]
   [dcg.api.db :as db]
   [dcg.api.utils :as utils]
   [dcg.api.resources.errors :as errors]
   [dcg.api.resources.card :refer [card-resource]]
   [dcg.api.resources.index :refer [index-resource]]
   [dcg.api.resources.release :refer [releases-resource release-resource]])
  (:gen-class))

(def routes
  [["/"
    {:name ::index
     :handler #'index-resource}]
   ["/:language/releases"
    {:name ::language
     :handler (fn [request]
                ((#'releases-resource request) request))}]
   ["/:language/releases/:release"
    {:name ::release
     :handler (fn [request]
                ((#'release-resource request) request))}]
   ["/:language/cards/:card-id"
    {:name ::card
     :handler (fn [request]
                ((#'card-resource request) request))}]])

(def route-handler
  (ring/ring-handler
   (ring/router routes {:conflicts nil})
   (ring/routes
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler {:not-found errors/error404
                                  :method-not-allowed errors/error405
                                  :not-acceptable errors/error406}))))

(defn wrap-cors
  [handler]
  (fn [request]
    (let [{:keys [status] :as response} (handler request)]
      (cond-> response
        (= status 200)
        (-> (assoc-in [:headers "Cache-Control"] (str "public, max-age="
                                                      (* 60 60 24 365)))
            (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, OPTIONS")
            (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
            (assoc-in [:headers "Access-Control-Max-Age"]
                      (str (* 60 60 24 365)))
            (assoc-in [:headers "Access-Control-Allow-Headers"]
                      (string/join ", "
                                   ["Accept"
                                    "Accept-Charset"
                                    "Accept-Language"
                                    "Cache-Control"
                                    "Content-Language"
                                    "Content-Type"])))))))

(def handler
  (-> #'route-handler
      (defaults/wrap-defaults (-> defaults/secure-site-defaults
                                  (assoc :cookies false)
                                  (assoc :session false)
                                  (assoc-in [:security :anti-forgery]
                                            {:error-response (errors/error403)})
                                  (assoc-in [:security :ssl-redirect] false)))
      wrap-cors))

(defn -main
  [& args]
  (let [port 3000]
    (db/import!)
    (jetty/run-jetty #'handler
                     {:join? false
                      :ssl? false
                      :port port})
    (println (format "API started on port %d" port))))
