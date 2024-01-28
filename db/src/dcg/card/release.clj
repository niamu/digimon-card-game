(ns dcg.card.release
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [dcg.card.utils :as utils]
   [hickory.core :as hickory]
   [hickory.select :as select]
   [taoensso.timbre :as logging])
  (:import
   [java.text ParseException SimpleDateFormat]
   [java.time LocalDate ZoneOffset]
   [java.net URI]
   [java.util Date]))

(defn- product
  [{:origin/keys [card-image-language language url]} dom-tree]
  (let [genre (-> (select/select
                   (select/descendant (select/class "genrename"))
                   dom-tree)
                  first
                  utils/text-content
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
                         (string/replace #"(.*?BOOSTER)?" "")
                         (string/replace #"(.*?ースター)?" "")
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
                         utils/text-content
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
                    (catch ParseException e nil)))]
    (when uri
      {:release/id (format "release/%s_%s" language uri-path)
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
                            utils/text-content
                            (string/split #"\n+"))
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
     :release/cardlist-uri (URI. (cond->> href
                                   (not (string/starts-with? href "http"))
                                   (str url "/cardlist/")))
     :release/language language
     :release/card-image-language (or card-image-language language)}))

(defmulti releases
  (fn [{:origin/keys [language] :as origin}]
    language))

(defmethod releases :default
  [{:origin/keys [url] :as origin}]
  (let [products (->> (utils/http-get (str url "/products/"))
                      hickory/parse
                      hickory/as-hickory
                      (select/select
                       (select/descendant (select/id "maincontent")
                                          (select/tag "article")))
                      (pmap (partial product origin)))
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
        (reduce (fn [accl r]
                  (if-let [p (some->> products
                                      (filter
                                       (fn [p]
                                         (name-matches? r p)))
                                      last)]
                    (conj accl
                          (merge r (dissoc p :release/id)))
                    (conj accl r)))
                []
                cardlist-releases)]
    merged))

(defmethod releases "zh-Hans"
  [{:origin/keys [url language card-image-language] :as origin}]
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
                   (map (fn [{:strs [id name productImage createTime productType]
                             :as p}]
                          (let [date-re #"[0-9]{4}\-[0-9]{2}\-[0-9]{2}"
                                date (-> (SimpleDateFormat. "yyyy-MM-dd")
                                         (.parse (re-find date-re createTime)))
                                product-uri (new URI
                                                 (.getScheme origin-uri)
                                                 (.getHost origin-uri)
                                                 "/info"
                                                 (format "id=%d" id)
                                                 nil)
                                release-name
                                (-> name
                                    (string/replace #".?数码宝贝卡牌对战.?\s*"
                                                    "")
                                    string/trim)
                                card-image-language (or card-image-language
                                                        language)]
                            {:release/id (format "release_%s_%s"
                                                 language id)
                             :release/name release-name
                             :release/genre productType
                             :release/date date
                             :release/language language
                             :release/image-uri (URI. productImage)
                             :release/card-image-language card-image-language
                             :release/product-uri product-uri}))))))
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
              (map (fn [{:strs [name createTime]}]
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
                                :release/card-image-language card-image-language
                                :release/cardlist-uri cardlist-uri}
                         (not= name "宣传卡") (assoc :release/date date)
                         (= name "宣传卡") (assoc :release/id
                                                  (format "release_%s_%s"
                                                          language 0)))))
                   releases)))
        merged
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
                    (conj accl (merge p
                                      release
                                      (select-keys p [:release/name
                                                      :release/genre])))
                    (conj accl p)))
                []
                products)
        cardlist-uris (->> (map :release/cardlist-uri merged)
                           (remove nil?)
                           set)
        missing (remove (fn [{:release/keys [cardlist-uri]}]
                          (contains? cardlist-uris cardlist-uri))
                        releases)]
    (concat merged missing)))
