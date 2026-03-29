(ns dcg.api.core
  (:require
   [clojure.string :as string]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults :as defaults]
   [dcg.api.db :as db]
   [dcg.api.utils :as utils]
   [dcg.api.resources.bulk-data :as bulk-data]
   [dcg.api.resources.errors :as errors]
   [dcg.api.router :as router]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.community.rotor :as rotor])
  (:import
   [java.io File]
   [org.eclipse.jetty.server.handler.gzip GzipHandler])
  (:gen-class))

(defn init-logging!
  []
  (timbre/merge-config!
   {:appenders
    {:println {:enabled? false}
     :rotor (rotor/rotor-appender {:path "logs/api.heroicc.log"
                                   :max-size (* 10 1024)})}}))

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

(defn log-requests
  [handler]
  (fn [{{:strs [user-agent]} :headers :as request}]
    (when-not (string/starts-with? user-agent "ELB-HealthChecker")
      (timbre/report request))
    (handler request)))

(def handler
  (-> (#'router/route-handler)
      (defaults/wrap-defaults (-> defaults/secure-site-defaults
                                  (assoc :cookies false)
                                  (assoc :session false)
                                  (assoc-in [:security :anti-forgery]
                                            {:error-response (errors/error403)})
                                  (assoc-in [:security :ssl-redirect] false)))
      wrap-default-language
      wrap-cors
      log-requests))

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
  [& _args]
  (db/import!)
  (bulk-data/export!)
  (println "Bulk data export complete"))

(defn start-server!
  [port]
  (jetty/run-jetty #'handler
                   {:join? false
                    :ssl? false
                    :port port
                    :send-date-header? false
                    :send-server-version? false
                    :configurator add-gzip-handler})
  (println (format "API started on port %d" port)))

(defn -main
  [& _args]
  (let [port 3000]
    (db/import!)
    (start-server! port)))
