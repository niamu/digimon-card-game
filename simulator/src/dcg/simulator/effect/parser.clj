(ns dcg.simulator.effect.parser
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.db.db :as db]
   [instaparse.core :as insta]
   [hickory.select :as select]))

(def ^:private parser
  (insta/parser (io/resource "card-parser_v2.bnf")
                :output-format :enlive
                :allow-namespaced-nts true))

(defn parse
  [text]
  (->> (string/replace text #"\n(?!\s*[\[<＜(\(Rule)])" " ")
       string/split-lines
       (reduce (fn [accl line]
                 (let [result (-> line
                                  string/trim
                                  parser)]
                   (cond-> accl
                     (insta/failure? result) (conj result)
                     (not (insta/failure? result))
                     (as-> #__ accl
                       (->> (concat accl result)
                            (into []))))))
               [])))

(defn transform
  [parsed]
  (->> parsed
       (insta/transform
        {:target/this (constantly :target/this)
         :target/previously-mentioned (constantly :target/previously-mentioned)
         :value/number parse-long
         :value/gte (constantly #'<=)
         :value/lte (constantly #'>=)
         :value/one (constantly 1)
         :value/none (constantly 0)
         :digimon (constantly :digimon)
         :option (constantly :option)
         :digi-egg (constantly :digi-egg)
         :tamer (constantly :tamer)
         :card/type (fn [v] {:card/type v})
         :card/dp (fn
                    ([v] {:card/dp v})
                    ([a1 a2]
                     {:card/dp (cons #'partial
                                     (sort-by number? [a1 a2]))}))
         :zone/security-cards (fn
                                ([v] {:card/dp v})
                                ([a1 a2]
                                 {:zone/security-cards (cons #'partial
                                                             (sort-by number? [a1 a2]))}))
         :player/memory (fn [v] {:player/memory v})
         :value/tree (fn
                       ([v] v)
                       ([a1 a2]
                        (cons #'partial
                              (sort-by number? [a1 a2]))))})))

(comment
  (->> (parse "[Your Turn][Once Per Turn] When an opponent's Digimon is deleted by dropping to 0 DP, this Digimon gets +1000 DP for the turn.\nGain 1 memory.")
       (filter (fn [effect]
                 (->> effect
                      (select/select
                       (select/child (select/tag :effect/timing)
                                     (select/tag :timing/your-turn)))
                      seq)))
       transform)

  (->> (db/q '{:find [[(pull ?c [:card/id
                                 :card/number
                                 :card/effect
                                 :card/inherited-effect
                                 :card/security-effect]) ...]]
               :where [[?c :card/number ?n]
                       (or [(clojure.string/starts-with? ?n "ST1-")]
                           #_[(clojure.string/starts-with? ?n "ST2-")]
                           #_[(clojure.string/starts-with? ?n "ST3-")]
                           #_[(clojure.string/starts-with? ?n "ST4-")])
                       [?c :card/parallel-id 0]
                       [?c :card/image ?i]
                       [?i :image/language "en"]]})
       (pmap (fn [{:card/keys [effect inherited-effect security-effect]
                  :as card}]
               (cond-> card
                 effect
                 (assoc-in [::card-effects :card/effect]
                           (parse effect))
                 inherited-effect
                 (assoc-in [::card-effects :card/inherited-effect]
                           (parse inherited-effect))
                 security-effect
                 (assoc-in [::card-effects :card/security-effect]
                           (parse security-effect)))))
       (filter (fn [{:card/keys [effect inherited-effect security-effect]}]
                 (or effect inherited-effect security-effect)))
       #_(filter (fn [{effects ::card-effects}]
                   (some #(some insta/failure? %) (vals effects)))))

  (db/import-from-file!)
  (time (let [cards (db/q '{:find [[(pull ?c [:card/number
                                              :card/effect
                                              :card/inherited-effect
                                              :card/security-effect]) ...]]
                            :in [$]
                            :where [[?c :card/image ?i]
                                    [?i :image/language ?l]
                                    [?c :card/language ?l]
                                    [?c :card/parallel-id 0]
                                    [?c :card/number ?n]
                                    [(clojure.string/starts-with? ?n "BT17-")]
                                    [?i :image/language "en"]]})
              failures
              (->> cards
                   (pmap (fn [{:card/keys [number] :as card}]
                           (let [failures
                                 (->> [(:card/effect card)
                                       (:card/inherited-effect card)
                                       (:card/security-effect card)]
                                      (remove nil?)
                                      (pmap (comp
                                             (fn [[s results]]
                                               (when (some insta/failure?
                                                           results)
                                                 s))
                                             (juxt identity parse)))
                                      (remove nil?))]
                             (when-not (empty? failures)
                               [number (into [] failures)]))))
                   (remove nil?)
                   (into []))]
          {:percentage (* (float (/ (- (count cards)
                                       (count failures))
                                    (count cards)))
                          100)
           :success (- (count cards)
                       (count failures))
           :total (count cards)
           :failures failures}))

  (defn format-effect
    "Format card text with line breaks matching the printed cards"
    [text]
    (let [s (->> text
                 string/split-lines
                 (map string/trim)
                 string/join)
          split-indexes
          (->> text
               parse
               (map insta/span)
               (remove nil?)
               (reduce (fn [accl [start end]]
                         (let [[prev-start prev-end]
                               (or (last accl) [0 0])]
                           (conj accl
                                 [(cond-> start
                                    (zero? start) (+ prev-end))
                                  (cond-> end
                                    (zero? start) (+ prev-end))])))
                       []))]
      (-> (->> split-indexes
               (map (fn [[start end]]
                      (string/trim (subs s start end))))
               (string/join "\n"))
          (string/replace #"\s*(・)" "\n$1"))))


  )
