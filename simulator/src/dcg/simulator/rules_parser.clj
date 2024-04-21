(ns dcg.simulator.rules-parser
  (:require
   [clojure.data.json :as json]
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

  (defn json
    [x]
    (with-open [out (io/writer (io/file "resources/cards.json"))]
      (json/write (doall x)
                  out
                  :value-fn (fn [k v]
                              (if (uri? v)
                                (str v)
                                v))
                  :indent true
                  :escape-slash false
                  :escape-unicode false)))

  (->> (d/q '{:find [[(pull ?c [:card/id
                                :card/name
                                :card/number
                                :card/category
                                :card/parallel-id
                                :card/level
                                :card/dp
                                :card/play-cost
                                :card/use-cost
                                :card/language
                                :card/form
                                :card/attribute
                                :card/type
                                :card/rarity
                                :card/notes
                                {:card/color [:color/index
                                              :color/color]}
                                {:card/digivolve-conditions [:digivolve/index
                                                             :digivolve/color
                                                             :digivolve/cost]}
                                {:card/releases [:release/name
                                                 :release/genre
                                                 :release/date]}
                                {:card/image [:image/source
                                              :image/language]}
                                {:card/highlights [:highlight/index
                                                   :highlight/field
                                                   :highlight/type
                                                   :highlight/text]}
                                :card/effect
                                :card/inherited-effect
                                :card/security-effect]) ...]]
              :in [$]
              :where [[?c :card/image ?i]
                      [?c :card/parallel-id 0]
                      [?c :card/number ?n]
                      (or [(clojure.string/starts-with? ?n "ST1-")]
                          [(clojure.string/starts-with? ?n "ST2-")]
                          [(clojure.string/starts-with? ?n "ST3-")])
                      [?i :image/language "en"]]}
            (d/db db/conn))
       (pmap (fn [{:card/keys [effect inherited-effect security-effect] :as card}]
               (let [process-fn (fn [text]
                                  {:text/raw text
                                   :text/tree (->> (parse text)
                                                   (apply concat)
                                                   (into []))})]
                 (-> (cond-> card
                       effect (update :card/effect process-fn)
                       inherited-effect (update :card/inherited-effect process-fn)
                       security-effect (update :card/security-effect process-fn))))))
       (into [])
       #_json)

  )
