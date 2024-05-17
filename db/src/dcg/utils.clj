(ns dcg.utils
  (:require
   [clj-http.client :as client]
   [clj-http.conn-mgr :as conn-mgr]
   [taoensso.timbre :as logging]))

(def ^:private connection-manager
  (conn-mgr/make-reusable-conn-manager {:timeout 30
                                        :default-per-route 10}))

(defn as-bytes
  [url]
  (-> (client/get url {:as :byte-array
                       :cookie-policy :standard
                       :retry-handler (fn [ex try-count _]
                                        (if (> try-count 2)
                                          (logging/error
                                           (format "Failed downloading: %s %s after %d attempts"
                                                   url
                                                   try-count))
                                          true))})
      :body))

(def http-get*
  (memoize (fn [url options]
             (logging/debug (format "Downloading: %s %s" url (pr-str options)))
             (-> (client/get url
                             (assoc options
                                    :cookie-policy :standard
                                    :throw-exceptions false
                                    :connection-manager connection-manager
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
                 :body))))

(defn http-get
  ([url]
   (http-get url {}))
  ([url options]
   (http-get* url options)))
