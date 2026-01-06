(ns dcg.db.utils
  (:require
   [clj-http.client :as client]
   [clj-http.conn-mgr :as conn-mgr]
   [dcg.db.aes :as aes]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.util Date]))

(def ^:private connection-manager
  (conn-mgr/make-reusable-conn-manager {:timeout 30
                                        :default-per-route 10}))

(def ^:private user-agent
  "Mozilla/5.0 (Windows NT 6.1;) Gecko/20100101 Firefox/13.0.1")

(defn as-bytes
  [url options]
  (let [response (client/get url
                             (merge (assoc-in options
                                              [:headers "User-Agent"]
                                              user-agent)
                                    {:as :byte-array
                                     :cookie-policy :standard
                                     :retry-handler (fn [_ try-count _]
                                                      (if (> try-count 2)
                                                        (logging/error
                                                         (format "Failed downloading: %s after %d attempts"
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
                              (-> options
                                  (assoc :cookie-policy :standard
                                         :throw-exceptions false
                                         :connection-manager connection-manager
                                         :retry-handler
                                         (fn [_ try-count _]
                                           (if (> try-count 2)
                                             (do (logging/error
                                                  (format "Failed POST: %s %s after %d attempts"
                                                          url
                                                          (pr-str options)
                                                          try-count))
                                                 false)
                                             true))
                                         :form-params form-params)
                                  (assoc-in [:headers "User-Agent"] user-agent)))
                 :body))))

(defn http-post
  ([url form-params]
   (http-post url form-params {}))
  ([url form-params options]
   (http-post* url form-params options)))

(defonce http-get*
  (memoize (fn [url options]
             (logging/debug (format "Downloading: %s %s" url (pr-str options)))
             (letfn [(fetch [url options]
                       (let [result (client/get
                                     url
                                     (-> options
                                         (assoc :cookie-policy :standard
                                                :throw-exceptions false
                                                :connection-manager connection-manager
                                                :retry-handler
                                                (fn [_ try-count _]
                                                  (if (> try-count 2)
                                                    (do (logging/error
                                                         (format "Failed GET: %s %s after %d attempts"
                                                                 url
                                                                 (pr-str options)
                                                                 try-count))
                                                        false)
                                                    true)))
                                         (assoc-in [:headers "User-Agent"] user-agent)))]
                         (if (or (= (:status result) 200)
                                 (and (= (get-in result [:headers "Content-Type"])
                                         "application/xml")
                                      (= (get-in result [:headers "Server"])
                                         "tencent-cos")))
                           (:body result)
                           (do (logging/debug "Retrying in 60 seconds...")
                               (Thread/sleep 60000)
                               (fetch url options)))))]
               (fetch url options)))))

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
                              (merge (assoc-in options
                                               [:headers "User-Agent"]
                                               user-agent)
                                     {:cookie-policy :standard
                                      :retry-handler (fn [_ try-count _]
                                                       (if (> try-count 2)
                                                         (logging/error
                                                          (format "Failed getting header: %s after %d attempts"
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
