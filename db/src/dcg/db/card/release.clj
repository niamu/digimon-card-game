(ns dcg.db.card.release
  (:require
   [clojure.edn :as edn]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [dcg.db.card.utils :as card-utils]
   [dcg.db.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.io PushbackReader]
   [java.text ParseException SimpleDateFormat]
   [java.time LocalDate ZoneOffset]
   [java.net URI]
   [java.util Date]))

(defn- download-image!
  [{:release/keys [id language image-uri] :as image}]
  (let [http-opts (utils/cupid-headers (str (.getScheme image-uri)
                                            "://"
                                            (.getHost image-uri)))
        filename (format "resources/images/releases/%s/image/%s.png"
                         language
                         (string/replace id (format "release_%s_" language) ""))]
    (when-not (.exists (io/file filename))
      (when-let [image-bytes (some-> (str image-uri)
                                     (utils/as-bytes http-opts)
                                     card-utils/trim-transparency!)]
        (.mkdirs (io/file (.getParent (io/file filename))))
        (with-open [in (io/input-stream image-bytes)
                    out (io/output-stream filename)]
          (io/copy in out))
        (logging/info (format "Downloaded image: [%s] %s" id (str image-uri)))))
    (-> image
        (dissoc :release/image-uri)
        (assoc :release/image
               {:image/id (string/replace id "release_" "image/release_")
                :image/language language
                :image/source image-uri
                :image/path filename}))))

(defn- download-thumbnail!
  [{:release/keys [id language image-uri] :as image}]
  (let [http-opts (utils/cupid-headers (str (.getScheme image-uri)
                                            "://"
                                            (.getHost image-uri)))
        filename (format "resources/images/releases/%s/thumbnail/%s.png"
                         language
                         (string/replace id (format "release_%s_" language) ""))]
    (when-not (.exists (io/file filename))
      (when-let [image-bytes (some-> (str image-uri)
                                     (utils/as-bytes http-opts)
                                     card-utils/trim-transparency!)]
        (.mkdirs (io/file (.getParent (io/file filename))))
        (with-open [in (io/input-stream image-bytes)
                    out (io/output-stream filename)]
          (io/copy in out))
        (logging/info (format "Downloaded thumbnail: [%s] %s" id (str image-uri)))))
    (-> image
        (dissoc :release/image-uri)
        (assoc :release/thumbnail
               {:image/id (string/replace id "release_" "thumbnail/release_")
                :image/language language
                :image/source image-uri
                :image/path filename}))))

(defn- product
  [{:origin/keys [card-image-language language url]} dom-tree]
  (let [genre (-> (select/select
                   (select/descendant (select/class "genrename"))
                   dom-tree)
                  first
                  card-utils/text-content
                  string/join
                  (string/replace "Expansion" "Booster")
                  (string/replace "拡張" "ブースター")
                  (string/replace "Start " "Starter ")
                  (string/replace "Advance " "Advanced "))
        release-name (-> (->> (select/select
                               (select/descendant (select/class "prodname"))
                               dom-tree)
                              first
                              :content
                              (filter (fn [s]
                                        (and (string? s)
                                             (not (string/starts-with?
                                                   s "デジモンカードゲー")))))
                              string/join)
                         (string/replace #"\h+" " ")
                         (string/replace "Digital Monster card game booster,"
                                         "")
                         (string/replace "Digital Monster card game," "")
                         (string/replace "start deck," "")
                         (string/replace "DIGIMON CARD GAME" "")
                         (string/replace "RELEASE " "")
                         (string/replace "\u2010" "-")
                         (string/replace "\uFF65" "\u30FB")
                         (string/replace #"(.*?BOOSTER(?!\s*\[))?" "")
                         (string/replace #"(.*?ースター(?!\s*【))?" "")
                         (string/replace #"(.*?부스터)?" "")
                         (string/replace #"(.*?덱)?" "")
                         #_(string/replace "BTK-1.0" "BTK-10")
                         (string/replace #"([aA-zZ])\[" "$1 [")
                         string/trim)
        uri (or (some->> (get-in dom-tree [:attrs :data-url])
                         (str url "/products/"))
                (some-> (select/select (select/descendant
                                        (select/class "btncol")
                                        (select/tag "a"))
                                       dom-tree)
                        first
                        (get-in [:attrs :href])
                        (as-> href
                            (if (string/starts-with? href "http")
                              href
                              (str url "/products/" href)))))
        uri-path (when uri
                   (string/replace (.getPath (URI. uri)) "/products/" ""))
        image (let [src (->> dom-tree
                             (select/select
                              (select/descendant (select/class "prodinfo")
                                                 (select/tag "img")))
                             first
                             :attrs
                             :src)]
                (->> (string/replace src #"^\.+" "")
                     (str url)
                     (URI.)))
        date-string (->> dom-tree
                         (select/select
                          (select/descendant (select/class "proddate")
                                             (select/tag "dd")))
                         first
                         card-utils/text-content
                         string/split-lines
                         (filter #(re-find #"[0-9]{4}" %))
                         first
                         ((fn [date-string]
                            (or (some-> date-string
                                        (string/replace #"^.*?\s+\-\s+" "")
                                        (string/replace #"^.*?:\s+" ""))
                                ""))))
        eastern-date-matcher #"(\d+)[年년]\s?(\d+)[月월]\s?(\d+)?[日일]?.*"
        date (if (re-matches eastern-date-matcher date-string)
               (let [[year month day]
                     (some->> (re-matches eastern-date-matcher date-string)
                              rest
                              (map #(Integer/parseInt (or % "1"))))]
                 (when (and year month day)
                   (as-> (LocalDate/of ^int year
                                       ^int month
                                       ^int day) d
                     (.atStartOfDay ^LocalDate d)
                     (.toInstant d ZoneOffset/UTC)
                     (Date/from d))))
               (try (.parse (if (> (count (re-seq #"[0-9]" date-string)) 4)
                              (SimpleDateFormat. "MMMM dd yyyy")
                              (SimpleDateFormat. "MMMM yyyy"))
                            (-> date-string
                                (string/replace #".*:\s+(.*)" "$1")
                                (string/replace #"([0-9]+)(th|st|nd|rd)"
                                                "$1")
                                (string/replace #",(\d)" ", $1")
                                (string/replace #"," "")))
                    (catch ParseException _ nil)))]
    (when uri
      {:release/id (format "release_%s_%s" language uri-path)
       :release/genre genre
       :release/name release-name
       :release/product-uri (URI. uri)
       :release/language language
       :release/card-image-language (or card-image-language language)
       :release/image-uri image
       :release/date date})))

(defn- release
  [{:origin/keys [card-image-language language url]}
   {{:keys [href]} :attrs :as dom-tree}]
  (let [[genre & title] (-> (select/select (select/descendant
                                            (select/and
                                             (select/tag "span")
                                             (select/class "title")))
                                           dom-tree)
                            first
                            card-utils/text-content
                            (string/split #"\n+"))
        img (-> (select/select (select/descendant
                                (select/class "thumb")
                                (select/tag "img"))
                               dom-tree)
                first
                (get-in [:attrs :src]))
        title (some-> title
                      string/join
                      (string/replace "\u2010" "-")
                      string/trim)
        id (some->> href
                    (re-find #"category=(\d+)")
                    second
                    parse-long)
        genre (string/replace genre
                              "Promotion card"
                              "Promotion Card")]
    {:release/id (format "release_%s_%s" language id)
     :release/name (if (or (nil? title)
                           (string/blank? title))
                     genre
                     title)
     :release/genre genre
     :release/image-uri (URI. (cond->> img
                                (not (string/starts-with? img "http"))
                                (str url "/cardlist/")))
     :release/cardlist-uri (URI. (cond->> href
                                   (not (string/starts-with? href "http"))
                                   (str url "/cardlist/")))
     :release/language language
     :release/card-image-language (or card-image-language language)}))

(defmulti releases
  (fn [{:origin/keys [language]}]
    language))

(defmethod releases :default
  [{:origin/keys [url language] :as origin}]
  (let [products
        (->> (utils/http-get (str url "/products/"))
             hickory/parse
             hickory/as-hickory
             (select/select
              (select/descendant (select/id "maincontent")
                                 (select/tag "article")))
             (pmap (partial product origin)))
        products (cond-> products
                   (= language "ja")
                   ;; NOTE: The Japanese site never included this product
                   ;; in the products page
                   (concat [{:release/name "リミテッドカードセット2024【LM-03】"
                             :release/genre "その他"
                             :release/date #inst "2024-03-02T05:00:00.000-00:00"
                             :release/image-uri (URI. "https://en.digimoncard.com/images/products/goods/limited_lm-03/img_pkg.png")}]))
        cardlist-releases (->> (utils/http-get (str url "/cardlist/"))
                               hickory/parse
                               hickory/as-hickory
                               (select/select
                                (select/descendant (select/id "snaviList")
                                                   (select/tag "li")
                                                   (select/tag "a")))
                               (map (partial release origin)))
        name-matches? (fn [r p]
                        (let [product-name (-> (:release/name p)
                                               (string/replace #"0([0-9])" "$1")
                                               (string/replace "-" "")
                                               (string/replace #"\s+【" "【")
                                               string/lower-case)
                              release-name (-> (:release/name r)
                                               (string/replace #"0([0-9])" "$1")
                                               (string/replace "-" "")
                                               (string/replace #"\s+【" "【")
                                               string/lower-case)]
                          (or (string/includes? release-name
                                                product-name)
                              (string/includes? product-name
                                                release-name))))
        merged
        (->> (reduce (fn [accl r]
                       (if-let [p (some->> products
                                           (filter (fn [p] (name-matches? r p)))
                                           last)]
                         (conj accl
                               (-> (merge (dissoc p :release/id) r)
                                   download-thumbnail!
                                   (merge (dissoc p :release/id))
                                   (cond-> #__
                                     (:release/image-uri p)
                                     download-image!)))
                         (conj accl (download-thumbnail! r))))
                     []
                     cardlist-releases)
             (map (fn [{:release/keys [name language] :as release}]
                    (assoc release :release/name
                           (if-let [code (->> name
                                              (re-find #"[\[【](.*)[\]】]")
                                              second)]
                             (string/replace name code
                                             (if (string/includes? code "-")
                                               (let [[card-set n]
                                                     (string/split code #"\-")]
                                                 (str card-set
                                                      "-"
                                                      (cond-> n
                                                        (< (count n) 2)
                                                        (->> parse-long
                                                             (format "%02d")))))
                                               (->> code
                                                    (re-find #"([A-Z]+)(.*)")
                                                    rest
                                                    (string/join "-"))))
                             (cond-> name
                               (or (= name "プロモーションカード")
                                   (= name "Promotion Card"))
                               (str (case language
                                      "ja" "【P】"
                                      "en" " [P]"))))))))
        merged-product-uris (set (map :release/product-uri merged))
        missing (->> products
                     (remove (fn [{:release/keys [product-uri]}]
                               (contains? merged-product-uris product-uri)))
                     (map (fn [product]
                            (download-image! product))))]
    (concat merged missing)))

(defmethod releases "ko"
  [{:origin/keys [url] :as origin}]
  (let [http-opts (utils/cupid-headers (str url "/products/"))
        products
        (->> (utils/http-get (str url "/products/")
                             http-opts)
             hickory/parse
             hickory/as-hickory
             (select/select
              (select/descendant (select/id "maincontent")
                                 (select/tag "article")))
             (pmap (partial product origin))
             (map (fn [{:release/keys [genre] :as r}]
                    (if (string/blank? genre)
                      (assoc r :release/genre "확장팩")
                      r)))
             (concat
              ;; NOTE: The Korean site removes older products.
              ;; Populate them from archived resources.
              (edn/read {:readers {'uri (fn [s]
                                          (URI. s))}}
                        (PushbackReader.
                         (io/reader
                          (io/resource "ko-products.edn")))))
             ;; deduplicate
             (reduce (fn [accl release]
                       (if (contains? (set (map :release/name accl))
                                      (:release/name release))
                         accl
                         (conj accl release)))
                     [])
             (map (fn [{:release/keys [id image-uri] :as product}]
                    (assoc product
                           :release/http-opts
                           http-opts))))
        ;; Store updated products archive
        _ (spit (io/resource "ko-products.edn")
                (->> products
                     (map #(dissoc % :release/http-opts))
                     pprint/pprint
                     with-out-str))
        ;; TODO: Save new ko-products after dedupe via name
        cardlist-releases (->> (utils/http-get (str url "/cardlist/")
                                               http-opts)
                               hickory/parse
                               hickory/as-hickory
                               (select/select
                                (select/descendant (select/id "snaviList")
                                                   (select/tag "li")
                                                   (select/tag "a")))
                               (map (comp #(assoc %
                                                  :release/http-opts
                                                  http-opts)
                                          (partial release origin))))
        name-matches? (fn [r p]
                        (let [product-name (-> (:release/name p)
                                               (string/replace "-0" "-")
                                               string/lower-case)
                              release-name (-> (:release/name r)
                                               (string/replace "-0" "-")
                                               string/lower-case)]
                          (or (string/includes? release-name
                                                product-name)
                              (string/includes? product-name
                                                release-name))))
        merged
        (->> (reduce (fn [accl r]
                       (if-let [p (some->> products
                                           (filter (fn [p] (name-matches? r p)))
                                           last)]
                         (conj accl
                               (-> (merge (dissoc p :release/id) r)
                                   download-thumbnail!
                                   (merge (dissoc p :release/id))
                                   (cond-> #__
                                     (:release/image-uri p)
                                     download-image!)))
                         (conj accl (download-thumbnail! r))))
                     []
                     cardlist-releases)
             (map (fn [{:release/keys [name] :as release}]
                    (assoc release :release/name
                           (if-let [code (->> name
                                              (re-find #"[\[【](.*)[\]】]")
                                              second)]
                             (string/replace name code
                                             (if (string/includes? code "-")
                                               (let [[card-set n]
                                                     (string/split code #"\-")]
                                                 (str card-set
                                                      "-"
                                                      (cond-> n
                                                        (< (count n) 2)
                                                        (->> parse-long
                                                             (format "%02d")))))
                                               (string/replace
                                                (->> code
                                                     (re-find #"([A-Z]+)(.*)")
                                                     rest
                                                     (string/join "-"))
                                                #"\-$" "")))
                             (cond-> name
                               (= name "프로모션 카드")
                               (str " [P]")))))))
        merged-product-uris (set (map :release/product-uri merged))
        missing (->> products
                     (remove (fn [{:release/keys [product-uri]}]
                               (contains? merged-product-uris product-uri)))
                     (map (fn [product]
                            (download-image! product))))]
    (concat merged missing)))

(defmethod releases "zh-Hans"
  [{:origin/keys [url language card-image-language]}]
  (let [origin-uri (new URI url)
        products-url (-> (new URI
                              (.getScheme origin-uri)
                              (string/replace (.getHost origin-uri)
                                              "www"
                                              "dtcgweb-api")
                              "/product/productinfomanage/cachelist"
                              nil
                              nil)
                         str)
        products
        (-> (utils/http-get products-url)
            json/read-str
            (get "list")
            (as-> #__ products
              (->> products
                   (map (fn [{:strs [id name productImage createTime productType]}]
                          (let [date-re #"[0-9]{4}\-[0-9]{2}\-[0-9]{2}"
                                date (-> (SimpleDateFormat. "yyyy-MM-dd")
                                         (.parse (re-find date-re createTime)))
                                product-uri (new URI
                                                 (.getScheme origin-uri)
                                                 (.getHost origin-uri)
                                                 "/info"
                                                 (format "id=%d" id)
                                                 nil)
                                genre productType
                                release-name
                                (-> name
                                    (string/replace #".?数码宝贝卡牌对战》?\s*"
                                                    "")
                                    (string/replace #"\h+" " ")
                                    (string/replace "START DECK" "")
                                    (string/replace (re-pattern
                                                     (format "(.*?%s)?" genre))
                                                    "")
                                    string/trim)
                                card-image-language (or card-image-language
                                                        language)]
                            (-> {:release/id (format "release_%s_%s"
                                                     language id)
                                 :release/name release-name
                                 :release/genre genre
                                 :release/date date
                                 :release/language language
                                 :release/image-uri (URI. productImage)
                                 :release/card-image-language card-image-language
                                 :release/product-uri product-uri}
                                download-image!)))))))
        releases-url (-> (new URI
                              (.getScheme origin-uri)
                              (string/replace (.getHost origin-uri)
                                              "www"
                                              "dtcgweb-api")
                              "/game/gamecard/weblist"
                              nil
                              nil)
                         str)
        releases
        (-> (utils/http-get releases-url)
            json/read-str
            (get "list")
            (as-> #__ releases
              (map (fn [{:strs [name createTime image]}]
                     (let [date-re #"[0-9]{4}\-[0-9]{2}\-[0-9]{2}"
                           date (-> (SimpleDateFormat. "yyyy-MM-dd")
                                    (.parse (re-find date-re createTime)))
                           card-image-language (or card-image-language
                                                   language)
                           cardlist-uri
                           (new URI
                                (.getScheme origin-uri)
                                (string/replace
                                 (.getHost origin-uri)
                                 "www" "dtcgweb-api")
                                "/gamecard/gamecardmanager/weblist"
                                (format "cardGroup=%s" name)
                                nil)]
                       (cond-> {:release/name name
                                :release/genre name
                                :release/language language
                                :release/image-uri (URI. image)
                                :release/card-image-language card-image-language
                                :release/cardlist-uri cardlist-uri}
                         (not= name "宣传卡") (assoc :release/date date)
                         (= name "宣传卡") (-> (assoc :release/id
                                                      (format "release_%s_%s"
                                                              language 0)
                                                      :release/name "宣传卡【P】")
                                               download-thumbnail!))))
                   releases)))
        merged
        (->> products
             (reduce (fn [accl p]
                       (if-let [release
                                (some->> releases
                                         (filter (fn [r]
                                                   (or (string/includes?
                                                        (:release/name r)
                                                        (:release/name p))
                                                       (string/includes?
                                                        (:release/name p)
                                                        (:release/name r)))))
                                         first)]
                         (conj accl
                               (-> (merge p
                                          release
                                          (select-keys p [:release/name
                                                          :release/genre]))
                                   download-thumbnail!))
                         (conj accl p)))
                     [])
             (map (fn [{:release/keys [name] :as release}]
                    (assoc release :release/name
                           (if-let [code (->> name
                                              (re-find #"[\[【](.*)[\]】]")
                                              second)]
                             (string/replace name code
                                             (if (string/includes? code "-")
                                               (let [[card-set n]
                                                     (string/split code #"\-")]
                                                 (str card-set
                                                      "-"
                                                      (cond-> n
                                                        (< (count n) 2)
                                                        (->> parse-long
                                                             (format "%02d")))))
                                               (->> code
                                                    (re-find #"([A-Z]+)(.*)")
                                                    rest
                                                    (string/join "-"))))
                             name)))))
        cardlist-uris (->> (map :release/cardlist-uri merged)
                           (remove nil?)
                           set)
        missing (remove (fn [{:release/keys [cardlist-uri]}]
                          (contains? cardlist-uris cardlist-uri))
                        releases)]
    (concat merged missing)))
