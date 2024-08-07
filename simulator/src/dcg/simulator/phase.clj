(ns dcg.simulator.phase
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.action :as action]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(defn unsuspend
  [{::game/keys [db] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [stack-uuids (->> (get-in db [::player/id
                                     turn
                                     ::player/areas
                                     ::area/battle
                                     ::area/stacks])
                         (map second)
                         (into #{}))]
    (-> (cond-> game
          ;; Only update the turn counter if we are on player 1.
          ;; Meaning we completed one complete round of the players
          (zero? (get-in db [::player/id turn ::player/turn-index]))
          (update ::game/turn-counter (fnil inc 0))
          (seq stack-uuids)
          (update-in [::game/db ::stack/uuid]
                     (fn [stacks]
                       (reduce-kv (fn [accl uuid {cards ::stack/cards :as stack}]
                                    (assoc accl uuid
                                           (cond-> stack
                                             (contains? stack-uuids uuid)
                                             (action/unsuspend-stack
                                              (map #(get-in db %) cards)))))
                                  {}
                                  stacks))))
        (update ::game/available-actions conj
                [:phase/draw [::player/id turn] nil])
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/draw))))

(defn draw
  [{::game/keys [db turn-counter]
    {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game game)]}
  (let [{{{deck-cards ::area/cards} ::area/deck} ::player/areas
         :as current-player} (get-in db [::player/id turn])
        {next-player-id ::player/id} (helpers/next-player game)
        deck-has-cards? (pos? (count deck-cards))]
    (cond-> game
      (and (not (and (= turn-counter 1)
                     (zero? (::player/turn-index current-player))))
           deck-has-cards?)
      (update-in [::game/db ::player/id turn ::player/areas]
                 (fn [{{deck-cards ::area/cards} ::area/deck :as areas}]
                   (-> areas
                       (update-in [::area/deck ::area/cards]
                                  (comp #(into [] %) rest))
                       (update-in [::area/hand ::area/cards] conj
                                  (first deck-cards)))))
      deck-has-cards?
      (-> (assoc ::game/available-actions
                 #{[:phase/breeding [::player/id turn] nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/breeding))
      (not deck-has-cards?)
      (-> (assoc ::game/available-actions #{})
          (assoc-in [::game/in ::game-in/state-id] :game/end)
          (update-in [::game/log] conj
                     [:game/end
                      [::player/id turn]
                      [::player/id next-player-id]])))))

(defn breeding
  [{::game/keys [db]
    {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{digi-eggs ::area/cards} ::area/digi-eggs
          {breeding ::area/stacks} ::area/breeding} ::player/areas
         :as player} (get-in db [::player/id turn])]
    (assoc game
           ::game/available-actions
           (cond-> #{[:phase/main [::player/id turn] nil]}
             (and (empty? breeding)
                  (seq digi-eggs))
             (conj [:action/hatch [::player/id turn] nil])
             (when breeding
               (some->> (first breeding)
                        (get-in db)
                        ::stack/cards
                        first
                        (get-in db)
                        ::card/card
                        :card/dp))
             (conj [:action/move [::player/id turn] [(first breeding)]])))))

(defn main
  [{::game/keys [db players] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{::player/keys [memory]
         {{cards-in-hand ::area/cards} ::area/hand
          {breeding ::area/stacks} ::area/breeding
          {battle ::area/stacks} ::area/battle} ::player/areas
         :as player} (get-in db [::player/id turn])
        available-memory (+ memory 10)
        colors-in-the-area
        (->> (concat breeding battle)
             (map #(get-in db %))
             (mapcat (fn [{::stack/keys [cards]}]
                       (->> cards
                            (mapcat (comp (fn [{{:card/keys [color]}
                                               ::card/card}]
                                            (map :color/color color))
                                          #(get-in db %))))))
             (into #{}))
        playable-cards (->> cards-in-hand
                            (filter (comp (fn [{{:card/keys [play-cost]}
                                               ::card/card}]
                                            (when play-cost
                                              (<= play-cost available-memory)))
                                          #(get-in db %)))
                            (map (fn [card]
                                   [:action/play [::player/id turn] [card]])))
        usable-cards
        (->> cards-in-hand
             (filter (comp (fn [{{:card/keys [color use-cost]} ::card/card}]
                             (when use-cost
                               (and (<= use-cost available-memory)
                                    (set/subset? (into #{} (map :color/color
                                                                color))
                                                 colors-in-the-area))))
                           #(get-in db %)))
             (map (fn [card]
                    [:action/use [::player/id turn] [card]])))
        aggressor-stacks
        (filter (comp (fn [{::stack/keys [uuid suspended? summoned? cards]}]
                        (let [{{:card/keys [dp]} ::card/card} (->> (first cards)
                                                                   (get-in db))]
                          (and (not suspended?)
                               (not summoned?)
                               dp)))
                      #(get-in db %))
                battle)
        attackable-players (->> players
                                (remove (fn [[_ id]]
                                          (= id turn)))
                                (into #{}))
        attackable-stacks
        (->> (::player/id db)
             (reduce-kv (fn [accl player-id player]
                          (cond-> accl
                            (contains? attackable-players
                                       [::player/id player-id])
                            (concat
                             (->> (get-in player [::player/areas
                                                  ::area/battle
                                                  ::area/stacks])
                                  (filter (comp (fn [{::stack/keys [cards
                                                                   suspended?]
                                                     :as stack}]
                                                  (and suspended?
                                                       (->> (first cards)
                                                            (get-in db)
                                                            ::card/card
                                                            :card/dp)))
                                                #(get-in db %)))))))
                        []))
        attack-actions
        (->> aggressor-stacks
             (mapcat (fn [stack]
                       (concat (map (fn [attackable-stack]
                                      [:action/attack.declare
                                       [::player/id turn]
                                       [stack attackable-stack]])
                                    attackable-stacks)
                               (map (fn [player]
                                      [:action/attack.declare
                                       [::player/id turn]
                                       [stack player]])
                                    attackable-players)))))
        digivolve-actions
        (let [the-area (->> (concat breeding battle)
                            (map (comp (fn [{::stack/keys [cards uuid]
                                            :as stack}]
                                         (let [[_ card-uuid] (first cards)]
                                           (assoc (get-in db (first cards))
                                                  ::card/stack uuid)))
                                       #(get-in db %))))]
          (->> cards-in-hand
               (map (fn [card]
                      (get-in db card)))
               (reduce (fn [accl
                           {::card/keys [uuid]
                            {:card/keys [digivolution-requirements]} ::card/card
                            :as card}]
                         (let [digivolvable
                               (mapcat
                                (fn [{:digivolve/keys [index cost]
                                     digivolve-color :digivolve/color
                                     digivolve-level :digivolve/level}]
                                  (->> the-area
                                       (filter
                                        (fn [{{:card/keys [color level]}
                                             ::card/card
                                             :as area-card}]
                                          (and (<= cost available-memory)
                                               (= level digivolve-level)
                                               (seq (set/intersection
                                                     (->> color
                                                          (map :color/color)
                                                          (into #{}))
                                                     (->> digivolve-color
                                                          (into #{})))))))
                                       (map (fn [card]
                                              [:action/digivolve
                                               [::player/id turn]
                                               [[::card/uuid uuid]
                                                index
                                                [::stack/uuid
                                                 (::card/stack card)]]]))))
                                digivolution-requirements)]
                           (cond-> accl
                             (seq digivolvable)
                             (concat digivolvable))))
                       [])))]
    (-> game
        (dissoc ::game/attack)
        (assoc ::game/available-actions
               (if (< memory 0)
                 #{[:phase/end-turn [::player/id turn] nil]}
                 (cond-> #{[:action/pass [::player/id turn] nil]}
                   (seq playable-cards)
                   (as-> #__ available-actions
                     (apply conj available-actions playable-cards))
                   (seq usable-cards)
                   (as-> #__ available-actions
                     (apply conj available-actions usable-cards))
                   (seq digivolve-actions)
                   (as-> #__ available-actions
                     (apply conj available-actions digivolve-actions))
                   (seq attack-actions)
                   (as-> #__ available-actions
                     (apply conj available-actions attack-actions))
                   ;; TODO
                   ;; - Activate a [Main] timing effect
                   )))
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/main))))

(defn end-of-turn
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (helpers/next-player game)]
    (-> game
        (assoc ::game/available-actions
               #{[:phase/unsuspend [::player/id next-player-id] nil]})
        (assoc-in [::game/in ::game-in/turn]
                  next-player-id)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/end-turn))))
