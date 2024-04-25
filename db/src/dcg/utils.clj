(ns dcg.utils
  (:require
   [clj-http.client :as client]
   [clj-http.conn-mgr :as conn-mgr]
   [taoensso.timbre :as logging]))

(def ^:private connection-manager
  (conn-mgr/make-reusable-conn-manager {:timeout 10
                                        :default-per-route 10}))

(defn as-bytes
  [url]
  (-> (client/get url {:as :byte-array
                       :socket-timeout 20000
                       :connection-timeout 20000
                       :cookie-policy :standard
                       :retry-handler (fn [ex try-count _]
                                        (if (> try-count 2)
                                          (logging/error
                                            (format "Failed downloading: %s %s after %d attempts"
                                                    url
                                                    try-count))
                                          true))})
      :body))

(defn http-head
  ([url]
   (http-head url {}))
  ([url options]
   (client/head url (assoc options
                           :socket-timeout 20000
                           :connection-timeout 20000
                           :cookie-policy :standard
                           :retry-handler (fn [ex try-count _]
                                            (if (> try-count 2)
                                              (do (logging/error
                                                    (format "Failed HEAD: %s after %d attempts"
                                                            url
                                                            try-count))
                                                  false)
                                              true))))))

(defn http-get
  ([url]
   (http-get url {}))
  ([url options]
   (logging/debug (format "Downloading: %s %s" url (pr-str options)))
   (-> (client/get url
                   (assoc options
                          :socket-timeout 20000
                          :connection-timeout 20000
                          :cookie-policy :standard
                          :throw-exceptions false
                          :retry-handler
                          (fn [ex try-count _]
                            (if (> try-count 2)
                              (do (logging/error
                                    (format "Failed GET: %s %s after %d attempts"
                                            url
                                            (pr-str options)
                                            try-count))
                                  false)
                              true))))
       :body)))
