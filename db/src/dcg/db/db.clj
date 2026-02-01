(ns dcg.db.db
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cognitect.transit :as transit]
   [datomic.client.api :as d])
  (:import
   [java.io PushbackReader Writer]
   [java.net URI]
   [java.util UUID]))

(def config
  {:server-type :datomic-local
   :storage-dir (.getPath (io/resource "db"))
   :system "dcg"})

(def client (d/client config))

(defmethod print-method URI
  [^URI v ^Writer w]
  (.write w (str "#uri " (pr-str (.toString v)))))

(def schema
  (concat
   ;; Card
   [{:db/ident :card/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/number
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/name
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/category
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/language
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/parallel-id
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/block-icon
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/play-cost
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/use-cost
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/level
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/dp
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/effect
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/inherited-effect
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/security-effect
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/form
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/attribute
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/type
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/rarity
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/supplemental-rarity
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/notes
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/pack-type
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/color
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/digivolution-requirements
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/releases
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/image
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/errata
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/limitations
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/icons
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/rules
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/faqs
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}
    {:db/ident :card/bandai-tcg+
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :card/panorama
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}]
   ;; Supplemental Rarity
   [{:db/ident :supplemental-rarity/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :supplemental-rarity/stamp
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :supplemental-rarity/stars
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}]
   ;; Color
   [{:db/ident :color/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :color/index
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :color/color
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}]
   ;; Digivolution Requirements
   [{:db/ident :digivolve/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :digivolve/index
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :digivolve/cost
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :digivolve/level
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :digivolve/category
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :digivolve/form
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :digivolve/color
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/many}]
   ;; Release
   [{:db/ident :release/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/name
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/genre
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/language
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/product-uri
     :db/valueType :db.type/uri
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/cardlist-uri
     :db/valueType :db.type/uri
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/image
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/thumbnail
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}
    {:db/ident :release/date
     :db/valueType :db.type/instant
     :db/cardinality :db.cardinality/one}]
   ;; Image
   [{:db/ident :image/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :image/language
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :image/source
     :db/valueType :db.type/uri
     :db/cardinality :db.cardinality/one}
    {:db/ident :image/path
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :image/hash
     :db/valueType :db.type/bigint
     :db/cardinality :db.cardinality/one}
    {:db/ident :image/hash-segments
     :db/valueType :db.type/tuple
     :db/tupleType :db.type/long
     :db/cardinality :db.cardinality/one}]
   ;; Errata
   [{:db/ident :errata/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :errata/date
     :db/valueType :db.type/instant
     :db/cardinality :db.cardinality/one}
    {:db/ident :errata/error
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :errata/correction
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :errata/notes
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}]
   ;; Limitation
   [{:db/ident :limitation/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :limitation/date
     :db/valueType :db.type/instant
     :db/cardinality :db.cardinality/one}
    {:db/ident :limitation/type
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :limitation/note
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :limitation/allowance
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :limitation/paired-card-numbers
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/many}]
   ;; Icon
   [{:db/ident :icon/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :icon/type
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :icon/field
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :icon/index
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :icon/text
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}]
   ;; Rules
   [{:db/ident :rule/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :rule/type
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :rule/value
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :rule/limitation
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}]
   ;; FAQs
   [{:db/ident :faq/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :faq/date
     :db/valueType :db.type/instant
     :db/cardinality :db.cardinality/one}
    {:db/ident :faq/question
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :faq/answer
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}]
   ;; Panoramas
   [{:db/ident :panorama/id
     :db/valueType :db.type/uuid
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :panorama/columns
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :panorama/order
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :panorama/cards
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}]))

(defonce conn
  (do (d/create-database client {:db-name "cards"})
      (let [conn (d/connect client {:db-name "cards"})]
        (d/transact conn {:tx-data schema})
        conn)))

(defn transact!
  [datoms]
  (doseq [batch (partition-all 100 datoms)]
    (d/transact conn {:tx-data batch})))

(defn q
  [query & inputs]
  (let [inputs (cons (d/db conn) inputs)]
    (apply d/q query inputs)))

(defn import-panoramas!
  []
  (let [panoramas (with-open [in (-> (io/resource "panorama.edn")
                                     io/reader)]
                    (edn/read (PushbackReader. in)))]
    (doseq [panorama panoramas
            :let [columns (count (first panorama))
                  ids (vec (flatten panorama))
                  id (UUID/nameUUIDFromBytes (.getBytes (apply str ids)))
                  cards (->> ids
                             (mapv (fn [card-id]
                                     [:card/id card-id])))]]
      (d/transact conn
                  {:tx-data [{:panorama/id id
                              :panorama/columns columns
                              :panorama/cards cards
                              :panorama/order (pr-str ids)}]})
      (d/transact conn
                  {:tx-data (vec (for [card-id ids]
                                   {:card/id card-id
                                    :card/panorama [:panorama/id id]}))}))))

(defn import!
  []
  (let [cards (with-open [in (io/input-stream "resources/cards.transit.json")]
                (transit/read (transit/reader in :json)))]
    (doseq [entities (partition-all 100 cards)]
      (transact! entities))
    (import-panoramas!)))

(defn- update-api-path
  [request path]
  (str (System/getenv "API_ORIGIN")
       path))

(defn- update-asset-path
  [request path]
  (when path
    (str (System/getenv "ASSETS_ORIGIN")
         path)))

(defn- update-image-path
  [path]
  (when path
    (str (System/getenv "IMAGES_ORIGIN")
         (-> path
             (string/replace #"\.png$" ".webp")
             (string/replace #"^/images" "")))))

(defn- update-release-images
  [release]
  (cond-> release
    (:release/image release)
    (update-in [:release/image :image/path]
               update-image-path)
    (:release/thumbnail release)
    (update-in [:release/thumbnail :image/path]
               update-image-path)))

(defn- update-card-images
  [card]
  (-> card
      (update-in [:card/image :image/path]
                 update-image-path)
      (update-in [:card/releases]
                 (fn [releases]
                   (map update-release-images releases)))))

(defn export!
  []
  (let [pull-query [:card/id
                    :card/number
                    :card/name
                    :card/category
                    :card/language
                    :card/parallel-id
                    :card/block-icon
                    :card/play-cost
                    :card/use-cost
                    :card/level
                    :card/dp
                    :card/effect
                    :card/inherited-effect
                    :card/security-effect
                    :card/form
                    :card/attribute
                    :card/type
                    :card/rarity
                    {:card/supplemental-rarity
                     [:supplemental-rarity/id
                      :supplemental-rarity/stamp
                      :supplemental-rarity/stars]}
                    :card/notes
                    :card/pack-type
                    {:card/color
                     [:color/id
                      :color/index
                      :color/color]}
                    {:card/digivolution-requirements
                     [:digivolve/id
                      :digivolve/index
                      :digivolve/cost
                      :digivolve/level
                      :digivolve/category
                      :digivolve/form
                      :digivolve/color]}
                    {:card/releases
                     [:release/id
                      :release/name
                      :release/genre
                      :release/language
                      :release/product-uri
                      :release/cardlist-uri
                      {:release/image
                       [:image/id
                        :image/language
                        :image/source
                        :image/path
                        :image/hash
                        :image/hash-segments]}
                      {:release/thumbnail
                       [:image/id
                        :image/language
                        :image/source
                        :image/path
                        :image/hash
                        :image/hash-segments]}
                      :release/date]}
                    {:card/image
                     [:image/id
                      :image/language
                      :image/source
                      :image/path
                      :image/hash
                      :image/hash-segments]}
                    {:card/errata
                     [:errata/id
                      :errata/date
                      :errata/error
                      :errata/correction
                      :errata/notes]}
                    {:card/limitations
                     [:limitation/id
                      :limitation/date
                      :limitation/type
                      :limitation/note
                      :limitation/allowance
                      :limitation/paired-card-numbers]}
                    {:card/icons
                     [:icon/id
                      :icon/type
                      :icon/field
                      :icon/index
                      :icon/text]}
                    {:card/rules
                     [:rule/id
                      :rule/type
                      :rule/value
                      {:rule/limitation
                       [:limitation/id
                        :limitation/date
                        :limitation/type
                        :limitation/note
                        :limitation/allowance
                        :limitation/paired-card-numbers]}]}
                    {:card/faqs
                     [:faq/id
                      :faq/date
                      :faq/question
                      :faq/answer]}
                    :card/bandai-tcg+
                    {:card/panorama
                     [:panorama/id
                      :panorama/columns
                      :panorama/order
                      {:panorama/cards
                       [:card/id]}]}]
        card-datoms (d/datoms (d/db conn) {:index :avet
                                           :components [:card/id]
                                           :limit -1})]
    (with-open [out (io/output-stream "resources/cards.transit.json")]
      (->> card-datoms
           (map (fn [{eid :e}]
                  (-> (d/pull (d/db conn) pull-query eid)
                      update-card-images)))
           (transit/write (transit/writer out :json))))))

(comment
  (d/delete-database client {:db-name "cards"})

  (doseq [id (->> (q '{:find [?rid]
                       :where [[?r :release/id ?rid]]})
                  (apply concat))]
    (d/transact conn {:tx-data [[:db/retractEntity [:release/id id]]]}))

  )
