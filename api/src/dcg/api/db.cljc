(ns dcg.api.db
  #?(:clj
     (:require
      [clojure.java.io :as io]
      [cognitect.transit :as transit]
      [datomic.api :as d]))
  #?(:clj
     (:import
      [com.cognitect.transit ReadHandler]
      [java.net URI])))

#?(:clj
   (def ^:private db-uri "datomic:mem://dcg"))

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
    {:db/ident :card/highlights
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
     :db/valueType :db.type/tuple
     :db/tupleType :db.type/string
     :db/cardinality :db.cardinality/one}]
   ;; Highlight
   [{:db/ident :highlight/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :highlight/type
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :highlight/field
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}
    {:db/ident :highlight/index
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}
    {:db/ident :highlight/text
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

#?(:clj
   (defonce conn
     (do (d/create-database db-uri)
         (let [conn (d/connect db-uri)]
           @(d/transact conn (vec schema))
           conn))))

#?(:clj
   (defn import!
     []
     (let [cards (with-open [in (-> (io/resource "cards.transit.json")
                                    io/input-stream)]
                   (transit/read (transit/reader in :json
                                                 {:handlers
                                                  {"r" (reify ReadHandler
                                                         (fromRep [_ rep]
                                                           (URI. rep)))}})))]
       (doseq [batch (partition-all 100 cards)]
         @(d/transact conn (vec batch))))))

#?(:clj
   (defn q
     [query & inputs]
     (let [inputs (cons (d/db conn) inputs)]
       (apply d/q query inputs))))

#?(:clj
   (defn pull
     [pattern eid]
     (d/pull (d/db conn) pattern eid)))
