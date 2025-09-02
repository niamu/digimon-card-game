(ns dcg.api.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [liberator.representation :as representation]
   [reitit.core :as r]
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults :as defaults]
   [taoensso.timbre :as logging]
   [dcg.api.db :as db]
   [dcg.api.routes :as routes]
   [dcg.api.utils :as utils]
   [dcg.api.resources.bulk-data :as bulk-data]
   [dcg.api.resources.errors :as errors]
   [dcg.api.router :as router])
  (:import
   [java.io File]
   [java.time ZonedDateTime]
   [org.eclipse.jetty.server.handler.gzip GzipHandler])
  (:gen-class))

(defn wrap-cors
  [handler]
  (fn [request]
    (let [{:keys [status] :as response} (handler request)]
      (cond-> response
        (= status 200)
        (-> (assoc-in [:headers "Cache-Control"]
                      "public, max-age=0, must-revalidate")
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
                                    "Content-Type"])))
        (instance? File (:body response))
        (-> (assoc-in [:headers "Content-Dispositon"] "attachment")
            (assoc-in [:headers "Etag"] (utils/sha (:uri request))))))))

(defn wrap-default-language
  [handler]
  (fn [request]
    (handler (-> request
                 (assoc ::default-language (utils/default-language request))))))

(def handler
  (-> (#'router/route-handler)
      (defaults/wrap-defaults (-> defaults/secure-site-defaults
                                  (assoc :cookies false)
                                  (assoc :session false)
                                  (assoc-in [:security :anti-forgery]
                                            {:error-response (errors/error403)})
                                  (assoc-in [:security :ssl-redirect] false)))
      wrap-default-language
      wrap-cors))

(defn- add-gzip-handler
  [server]
  (.setHandler server
               (doto (GzipHandler.)
                 (.setHandler (.getHandler server))
                 (.setIncludedMimeTypes
                  (into-array ["application/json"
                               "application/vnd.api+json"]))
                 (.setMinGzipSize 860))))

(defn bulk-data-export!
  [& args]
  (db/import!)
  (bulk-data/export!)
  (println "Bulk data export complete"))

(defn -main
  [& args]
  (let [port 3000]
    (db/import!)
    (jetty/run-jetty #'handler
                     {:join? false
                      :ssl? false
                      :port port
                      :send-date-header? false
                      :send-server-version? false
                      :configurator add-gzip-handler})
    (println (format "API started on port %d" port))))
