(ns dcg.simulator.rules-parser
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [instaparse.core :as insta]))

(def parser (insta/parser (io/resource "parser.bnf")))

(defn parse
  [effect]
  (->> (string/replace effect #"\n(?!\[)" "")
       string/split-lines
       (map parser)))

(comment
  (require '[datomic.api :as d]
           '[dcg.db :as db])
  (db/import-from-file!)
  (let [m (->> (d/q '{:find [[(pull ?c [:card/name
                                        :card/number
                                        :card/effect
                                        :card/inherited-effect
                                        :card/security-effect
                                        {:card/highlights
                                         [:highlight/id
                                          :highlight/type
                                          :highlight/field
                                          :highlight/index
                                          :highlight/text]}]) ...]]
                      :in [$]
                      :where [[?c :card/image ?i]
                              [?c :card/parallel-id 0]
                              (or [?c :card/effect _]
                                  [?c :card/inherited-effect _]
                                  [?c :card/security-effect _])
                              [?c :card/number ?n]
                              (or [(clojure.string/starts-with? ?n "ST1-")]
                                  [(clojure.string/starts-with? ?n "ST2-")]
                                  [(clojure.string/starts-with? ?n "ST3-")]
                                  [(clojure.string/starts-with? ?n "ST4-")]
                                  [(clojure.string/starts-with? ?n "ST5-")]
                                  [(clojure.string/starts-with? ?n "ST6-")]
                                  [(clojure.string/starts-with? ?n "ST7-")]
                                  [(clojure.string/starts-with? ?n "ST8-")]
                                  [(clojure.string/starts-with? ?n "ST9-")]
                                  [(clojure.string/starts-with? ?n "ST10-")]
                                  [(clojure.string/starts-with? ?n "ST12-")]
                                  [(clojure.string/starts-with? ?n "ST13-")]
                                  [(clojure.string/starts-with? ?n "BT1-")]
                                  [(clojure.string/starts-with? ?n "BT2-")]
                                  [(clojure.string/starts-with? ?n "BT3-")]
                                  [(clojure.string/starts-with? ?n "BT4-")]
                                  [(clojure.string/starts-with? ?n "BT5-")]
                                  [(clojure.string/starts-with? ?n "BT6-")]
                                  [(clojure.string/starts-with? ?n "EX1-")]
                                  [(clojure.string/starts-with? ?n "BT7-")]
                                  [(clojure.string/starts-with? ?n "BT8-")]
                                  [(clojure.string/starts-with? ?n "EX2-")]
                                  [(clojure.string/starts-with? ?n "BT9-")]
                                  [(clojure.string/starts-with? ?n "BT10-")]
                                  [(clojure.string/starts-with? ?n "EX3-")]
                                  [(clojure.string/starts-with? ?n "BT11-")])
                              #_[(clojure.string/starts-with? ?n "ST14-")]
                              [?i :image/language "en"]]}
                    (d/db db/conn))
               (mapcat (juxt :card/effect
                             :card/inherited-effect
                             :card/security-effect))
               (remove nil?)
               (reduce (fn [accl s]
                         (let [results (parse s)
                               processed? (boolean (some insta/failure? results))]
                           (update accl
                                   processed?
                                   conj
                                   s)))
                       {true []
                        false []}))
        result (update-vals m count)]
    {:percentage (when (get result false)
                   (* (float (/ (get result false)
                                (+ (get result false)
                                   (get result true 0))))
                      100))
     :success (get result false 0)
     :total (+ (get result false 0)
               (get result true 0))}
    #_(get m false)
    (->> (get m true)
         sort))


  {:percentage 85.25621891021729, :success 2845, :total 3337}
  )
