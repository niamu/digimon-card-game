(ns dcg.card.rules
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dcg.db :as db]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
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

(defn transform
  [parse-tree]
  (->> parse-tree
       (insta/transform
        {:EFFECT (fn [& args]
                   (if (every? map? args)
                     (->> (into [] args)
                          (apply merge-with
                                 (comp vec (fnil concat []))))
                     args))
         :timing (fn [& args]
                   {:effect/timing (into #{} args)})
         :timing_main (fn [] :timing/main)
         :timing_security (fn [] :timing/security)
         :timing_your-turn (fn [] :timing/your-turn)
         :timing_when-digivolving (fn [] :timing/when-digivolving)
         :timing_when-attacking (fn [] :timing/when-attacking)
         :without-paying-memory-cost (fn [] {:cost 0})
         :play (fn [& args]
                 {:effect/action [:action/play args]}
                 #_{:effect/function
                    (list 'with-meta
                          (list 'fn ['{{::game-in/keys [turn]} ::game/in
                                       :as game}]
                                (list 'dcg.simulator.action/play
                                      'game
                                      [:action/play 'turn (into [] args)]))
                          {:effect/based :action/play})})
         :itself (fn [& args]
                   (if (or (empty? (remove nil? args))
                           (= '(:digimon) args))
                     nil
                     [:itself "TODO" args]))
         :target (fn [& args]
                   (cond
                     (= 'fn (ffirst args)) {:effect/target (first args)}
                     (= '(nil) args) {:effect/target (random-uuid)} #_(::card/uuid card)
                     :else [:target "TODO" args]))}
        #_{:EFFECT (fn [& args]
                     {:action/effect (if (every? map? args)
                                       (->> (into [] args)
                                            (apply merge-with
                                                   (comp vec (fnil concat []))))
                                       args)})
           :keyword-effect (fn [& f] {:effect/function f})
           :blocker (fn [& args]
                      (list 'with-meta
                            (list 'fn ['card]
                                  (list 'assoc-in
                                        'card
                                        [:card/modifiers
                                         :card/blocker]
                                        true))
                            {:effect/based :keyword-effect/blocker}))
           :security-attack (fn [& value]
                              (list 'with-meta
                                    (list 'fn ['card]
                                          (list 'update-in
                                                'card
                                                [:card/modifiers
                                                 :card/security-effect]
                                                (list 'fnil value 0)))
                                    {:effect/based :keyword-effect/security-effect}))
           :timing (fn [& args]
                     {:effect/timing (into #{} args)})
           :timing_main (fn [] :timing/main)
           :timing_security (fn [] :timing/security)
           :timing_your-turn (fn [] :timing/your-turn)
           :timing_when-digivolving (fn [] :timing/when-digivolving)
           :timing_when-attacking (fn [] :timing/when-attacking)
           ;; GTE/LTE needs to support `:equate-with`
           :GTE (fn [] '>=)
           :LTE (fn [] '<=)
           :OPERATOR (fn [op]
                       (case op
                         "+" '+
                         "-" '-))
           :you (fn [& args]
                  [:you args])
           :ALL (fn []
                  :ALL)
           :value (fn [& args]
                    (let [args (->> args
                                    (map (fn [x]
                                           (if (and (string? x)
                                                    (re-find #"[0-9]+" x))
                                             (parse-long x)
                                             x)))
                                    (sort-by number?)
                                    (into []))
                          [f & rest] args]
                      (cond
                        (number? f) args
                        (keyword? f) (first args)
                        :else (list 'fn '[v]
                                    (list 'apply f 'v (into [] rest))))))
           :number parse-long
           :DP (fn [& value]
                 {:effect/function
                  (list 'with-meta
                        (list 'fn ['card]
                              (list 'update-in
                                    'card
                                    [:card/modifiers
                                     :card/dp]
                                    (list 'fnil value 0)))
                        {:effect/based :card/dp})})
           :any_card-type (fn [& args]
                            (when-not (nil? args)
                              [:category/any "TODO" args]))
           :digimon_card-type (fn [& args]
                                (if (nil? args)
                                  :digimon
                                  [:digimon "TODO" args]))
           :card-type (fn [& args]
                        (cond
                          (= (count args) 1) (first args)
                          (empty? (remove nil? args)) nil
                          :else [:card/category "TODO" args]))
           :memory (fn [& value]
                     [:player/memory (first value)])
           :gain_lose (fn [& args]
                        (if (some (fn [[k & _]]
                                    (= k :conditional))
                                  args)
                          [:gain_lose "TODO"
                           (list 'if :conditional
                                 args)]
                          (first args)))
           :gain (fn [& args]
                   (let [{:effect/keys [target function] :as effect}
                         (apply merge args)]
                     (if (uuid? target)
                       {:effect/function function
                        :effect/target target}
                       [:gain "TODO" effect])))
           :lose (fn [& [[memory value]]]
                   {:effect/function (list 'with-meta
                                           (list 'fn ['game]
                                                 (list 'dcg.simulator.action/update-memory
                                                       'game
                                                       '-
                                                       value))
                                           {:effect/based :action/update-memory})})
           :during-turn (fn [v]
                          (keyword "during" (name (first v))))})
       (into [])))

(defn effect-on-card
  [card field index]
  (let [text (get card field)
        effect (->> (parse text)
                    transform)]
    (nth effect index)))

(comment
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

  (->> (db/q '{:find [[(pull ?c [:card/id
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
                                 :card/block-icon
                                 :card/notes
                                 {:card/color [:color/index
                                               :color/color]}
                                 {:card/digivolution-requirements [:digivolve/index
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
               :where [[?c :card/image ?i]
                       [?c :card/parallel-id 0]
                       [?c :card/number ?n]
                       (or [(clojure.string/starts-with? ?n "ST1-")]
                           #_[(clojure.string/starts-with? ?n "ST2-")]
                           #_[(clojure.string/starts-with? ?n "ST3-")])
                       [?i :image/language "en"]]})
       (pmap (fn [{:card/keys [effect inherited-effect security-effect] :as card}]
               (let [process-fn (fn [text]
                                  {:text/raw text
                                   :text/tree (parse text)})]
                 (-> (cond-> card
                       effect           (update :card/effect
                                                format-effect)
                       inherited-effect (update :card/inherited-effect
                                                format-effect)
                       security-effect  (update :card/security-effect
                                                format-effect))))))
       (filter (fn [{:card/keys [effect inherited-effect security-effect]}]
                 (or effect inherited-effect security-effect)))
       (mapcat (juxt :card/effect
                     :card/inherited-effect
                     :card/security-effect))
       (remove nil?)
       (into #{})
       (map (juxt identity
                  parse
                  (comp transform parse))))

  (let [card (db/q '{:find [(pull ?c [:card/id
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
                                      :card/block-icon
                                      :card/notes
                                      {:card/color [:color/index
                                                    :color/color]}
                                      {:card/digivolution-requirements
                                       [:digivolve/index
                                        :digivolve/color
                                        :digivolve/cost]}
                                      {:card/releases [:release/name
                                                       :release/genre
                                                       :release/date]}
                                      #_#_{:card/image [:image/source
                                                        :image/language]}
                                      {:card/highlights [:highlight/index
                                                         :highlight/field
                                                         :highlight/type
                                                         :highlight/text]}
                                      :card/effect
                                      :card/inherited-effect
                                      :card/security-effect]) .]
                     :where [[?c :card/image ?i]
                             [?c :card/parallel-id 0]
                             [?c :card/number "ST1-12"]
                             [?i :image/language "en"]]})]
    (effect-on-card card :card/security-effect 0))

  (-> "[Security] Play this card without paying its memory cost."
      parse
      transform)

  )
