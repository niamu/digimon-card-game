(ns dcg.simulator.effect.parser
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.db.db :as db]
   [instaparse.core :as insta]))

(defonce ^:private parser
  (insta/parser (io/resource "card-parser.bnf")
                :output-format :enlive))

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
                     (not (insta/failure? result)) (as-> #__ accl
                                                     (->> (concat accl result)
                                                          (into []))))))
               [])))

(defn transform
  [text]
  (->> (parse text)
       (insta/transform
        {:EFFECT (fn [& args]
                   (if (every? map? args)
                     (apply merge-with (fn [& xs] (into #{} xs)) args)
                     args))
         :target (fn [& args]
                   {:target (if (= (count args) 1)
                              (first args)
                              args)})
         :itself (fn [& args]
                   (if (zero? (count args))
                     :itself
                     {:itself args}))
         :number parse-long
         :DP (fn [& args]
               {:card/dp (if (= (count args) 1)
                           (first args)
                           args)})
         :value (fn [& args]
                  (if (= (count args) 1)
                    (first args)
                    args))
         :gain_lose (fn [& args]
                      (if (= (count args) 1)
                        (first args)
                        args))
         :gain (fn [& args]
                 {:effect/gain args})
         :lose-memory (fn [v]
                        {:effect/action [:action/update-memory
                                         'player-id
                                         ['- v]]})
         :memory (fn [& args]
                   [:player/memory (if (= (count args) 1)
                                     (first args)
                                     args)])
         :timing (fn [& args]
                   {:effect/timing (into #{} args)})
         :timing_main (fn [] :main)
         :timing_on-play (fn [] :on-play)
         :timing_security (fn [] :security)
         :timing_your-turn (fn [] :your-turn)
         :timing_when-digivolving (fn [] :when-digivolving)
         :timing_when-attacking (fn [] :when-attacking)
         :without-paying-memory-cost (fn [] {:cost 0})
         :play (fn [& args]
                 {:effect/action [:action/play args]})
         :keyword-effect (fn [k]
                           {:effect/keyword k})
         :draw (fn [value]
                 {:keyword/name :draw
                  :keyword/value value})
         :blocker (fn [] {:keyword/name :blocker})
         :security-attack (fn [value]
                            {:keyword/name :security-attack
                             :keyword/value value})})
       (map-indexed (fn [idx m]
                      (cond-> m
                        (map? m)
                        (assoc :effect/index idx))))
       (into [])))

(comment
  (->> (db/q '{:find [[(pull ?c [:card/id
                                 :card/number
                                 :card/effect
                                 :card/inherited-effect
                                 :card/security-effect]) ...]]
               :where [[?c :card/image ?i]
                       [?c :card/parallel-id 0]
                       [?c :card/number ?n]
                       [(clojure.string/starts-with? ?n "ST1-")]
                       [?i :image/language "en"]]})
       (pmap (fn [{:card/keys [effect inherited-effect security-effect]
                  :as card}]
               (cond-> card
                 effect           (assoc-in [::card-effects :card/effect]
                                            (transform effect))
                 inherited-effect (assoc-in [::card-effects :card/inherited-effect]
                                            (transform inherited-effect))
                 security-effect  (assoc-in [::card-effects :card/security-effect]
                                            (transform security-effect)))))
       (filter (fn [{:card/keys [effect inherited-effect security-effect]}]
                 (or effect inherited-effect security-effect))))

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
                                    #_[(clojure.string/starts-with? ?n "BT16-")]
                                    [?i :image/language "en"]]})
              failures (->> cards
                            (pmap (fn [{:card/keys [number] :as card}]
                                    (let [failures
                                          (->> [(:card/effect card)
                                                (:card/inherited-effect card)
                                                (:card/security-effect card)]
                                               (remove nil?)
                                               (pmap (comp (fn [[s results]]
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
    [text]
    (let [s (->> text
                 string/split-lines
                 (map string/trim)
                 string/join)
          split-indexes (->> text
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
