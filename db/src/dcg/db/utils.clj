(ns dcg.db.utils
  (:require
   [clj-http.client :as client]
   [clj-http.conn-mgr :as conn-mgr]
   [dcg.db.aes :as aes]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.text ParseException SimpleDateFormat]
   [java.util Date TimeZone]))

(def ^:private connection-manager
  (conn-mgr/make-reusable-conn-manager {:timeout 30
                                        :default-per-route 10}))

(defn as-bytes
  [url options]
  (let [response (client/get url
                             (merge options
                                    {:as :byte-array
                                     :cookie-policy :standard
                                     :retry-handler (fn [ex try-count _]
                                                      (if (> try-count 2)
                                                        (logging/error
                                                          (format "Failed downloading: %s %s after %d attempts"
                                                                  url
                                                                  try-count))
                                                        true))
                                     :throw-exceptions? false}))]
    (if (= (:status response) 200)
      (:body response)
      (logging/error (format "Error downloading: %s" url)))))

(defonce http-post*
  (memoize (fn [url form-params options]
             (logging/debug (format "POST: %s %s %s"
                                    url form-params (pr-str options)))
             (-> (client/post url
                              (assoc options
                                     :cookie-policy :standard
                                     :throw-exceptions false
                                     :connection-manager connection-manager
                                     :retry-handler
                                     (fn [ex try-count _]
                                       (if (> try-count 2)
                                         (do (logging/error
                                               (format "Failed POST: %s %s after %d attempts"
                                                       url
                                                       (pr-str options)
                                                       try-count))
                                             false)
                                         true))
                                     :form-params form-params))
                 :body))))

(defn http-post
  ([url form-params]
   (http-post url form-params {}))
  ([url form-params options]
   (http-post* url form-params options)))

(defonce http-get*
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

(defn cupid-headers
  [url]
  (if-let [script (some->> (http-get url)
                           hickory/parse
                           hickory/as-hickory
                           (select/select
                            (select/descendant
                             (select/follow-adjacent
                              (select/and (select/tag "script")
                                          (select/attr :src #(= % "/cupid.js")))
                              (select/tag "script"))))
                           first
                           :content
                           first)]
    (let [[[_ a] [_ b] [_ c]] (re-seq #"=toNumbers\(\"([0-9a-z]+)\"\)" script)
          cupid (aes/decrypt c a b)]
      {:headers {"Cookie" (format "CUPID=%s" cupid)}})
    {}))

(defonce last-modified*
  (memoize (fn [url options]
             (logging/debug (format "Requesting HEAD: %s %s" url (pr-str options)))
             (-> (client/head url
                              (merge options
                                     {:cookie-policy :standard
                                      :retry-handler (fn [ex try-count _]
                                                       (if (> try-count 2)
                                                         (logging/error
                                                           (format "Failed getting header: %s %s after %d attempts"
                                                                   url
                                                                   try-count))
                                                         true))
                                      :throw-exceptions? false}))
                 :headers
                 (get "Last-Modified" (str (Date.)))
                 (Date.)))))

(defn last-modified
  ([url]
   (last-modified (str url) (cupid-headers (str (.getScheme url)
                                                "://"
                                                (.getHost url)))))
  ([url options]
   (last-modified* url options)))

(defn partition-at
  [f coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (let [run (cons (first s) (take-while #(not (f %)) (rest s)))]
       (cons run (partition-at f (drop (count run) s)))))))
