(ns dcg.simulator.rules-parser
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [instaparse.core :as insta]))

(def parser (insta/parser (io/resource "parser.bnf")))

(defn parse
  [effect]
  (->> (string/replace effect #"\n(?!\s*[\[<＜])" " ")
       string/split-lines
       (map (comp parser string/trim))))

(comment
  (require '[datomic.api :as d]
           '[dcg.db :as db])
  (db/import-from-file!)
  (let [m (->> (d/q '{:find [[(pull ?c [:card/id
                                        :card/effect
                                        :card/inherited-effect
                                        :card/security-effect]) ...]]
                      :in [$]
                      :where [[?c :card/image ?i]
                              [?c :card/parallel-id 0]
                              (or [?c :card/effect _]
                                  [?c :card/inherited-effect _]
                                  [?c :card/security-effect _])
                              [?c :card/number ?n]
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
    (get m true))

  )
