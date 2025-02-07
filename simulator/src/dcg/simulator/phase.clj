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

(defn start-of-turn
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (->> (mod (inc turn-index) (count players))
                                          (nth players))]
    (-> game
        (assoc ::game/available-actions
               #{[:phase/unsuspend [::player/id next-player-id] nil]})
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/start-turn))))

(defn unsuspend
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{current-player-id ::player/id} (nth players turn-index)]
    (-> game
        (update ::game/turn-counter (fnil inc 0))
        (update-in [::game/players
                    turn-index
                    ::player/areas
                    ::area/battle
                    ::area/stacks]
                   (fn [stacks]
                     (mapv action/unsuspend-stack stacks)))
        (update ::game/available-actions conj
                [:phase/draw [::player/id current-player-id] nil])
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/draw))))

(defn draw
  [{::game/keys [turn-counter players] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game game)]}
  (let [{{{deck-cards ::area/cards} ::area/deck} ::player/areas
         current-player-id ::player/id} (nth players turn-index)
        {next-player-id ::player/id} (->> (mod (inc turn-index) (count players))
                                          (nth players))
        deck-has-cards? (pos? (count deck-cards))]
    (cond-> game
      ;; Draw a card if this isn't the first turn of the first player
      (and (not (and (= turn-counter 1)
                     (zero? turn-index)))
           deck-has-cards?)
      (update-in [::game/players turn-index ::player/areas]
                 (fn [{{deck-cards ::area/cards} ::area/deck :as areas}]
                   (-> areas
                       (update-in [::area/deck ::area/cards] rest)
                       (update-in [::area/hand ::area/cards] conj
                                  (first deck-cards)))))
      deck-has-cards?
      (-> (assoc ::game/available-actions
                 #{[:phase/breeding [::player/id current-player-id] nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/breeding))
      (not deck-has-cards?)
      (-> (assoc ::game/available-actions #{})
          (assoc-in [::game/in ::game-in/state-id] :game/end)
          (update-in [::game/log] conj
                     [:game/end
                      [::player/id current-player-id]
                      [::player/id next-player-id]])))))

(defn breeding
  [{::game/keys [players cards-lookup] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{digi-eggs ::area/cards} ::area/digi-eggs
          {breeding ::area/stacks} ::area/breeding} ::player/areas
         current-player-id ::player/id
         :as current-player} (nth players turn-index)]
    (assoc game
           ::game/available-actions
           (cond-> #{[:phase/main [::player/id current-player-id] nil]}
             (and (empty? breeding)
                  (seq digi-eggs))
             (conj [:action/hatch [::player/id current-player-id] nil])
             (some->> breeding
                      first
                      ::stack/cards
                      first
                      (helpers/card-lookup cards-lookup)
                      :card/dp)
             (conj [:action/move [::player/id current-player-id]
                    [::stack/uuid (::stack/uuid (first breeding))]])))))

(defn main
  [{::game/keys [players cards-lookup] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{::player/keys [memory]
         {{cards-in-hand ::area/cards} ::area/hand
          {breeding ::area/stacks} ::area/breeding
          {battle ::area/stacks} ::area/battle} ::player/areas
         current-player-id ::player/id
         :as player} (nth players turn-index)
        available-memory (+ memory 10)
        colors-in-the-area
        (->> (concat breeding battle)
             (mapcat (fn [{::stack/keys [cards]}]
                       (->> cards
                            (map (partial helpers/card-lookup cards-lookup))
                            (mapcat (fn [{:card/keys [color]}]
                                      (map :color/color color))))))
             (into #{}))
        playable-cards (->> cards-in-hand
                            (filter (comp (fn [{:card/keys [play-cost]}]
                                            (when play-cost
                                              (<= play-cost available-memory)))
                                          (partial helpers/card-lookup
                                                   cards-lookup)))
                            (map (fn [{::card/keys [uuid]}]
                                   [:action/play [::player/id current-player-id]
                                    [::card/uuid uuid]])))
        usable-cards
        (->> cards-in-hand
             (filter (comp (fn [{:card/keys [color use-cost]}]
                             (when use-cost
                               (and (<= use-cost available-memory)
                                    (set/subset? (into #{} (map :color/color
                                                                color))
                                                 colors-in-the-area))))
                           (partial helpers/card-lookup cards-lookup)))
             (map (fn [{::card/keys [uuid]}]
                    [:action/use [::player/id current-player-id]
                     [::card/uuid uuid]])))
        aggressor-stacks
        (filter (fn [{::stack/keys [uuid suspended? summoned? cards]}]
                  (let [{:card/keys [dp]} (->> (first cards)
                                               (helpers/card-lookup cards-lookup))]
                    (and (not suspended?)
                         (not summoned?)
                         dp)))
                battle)
        attackable-players (->> players
                                (map ::player/id)
                                (remove (fn [id] (= id current-player-id)))
                                (into #{}))
        attackable-stacks
        (->> players
             (reduce (fn [accl {player-id ::player/id :as player}]
                       (cond-> accl
                         (contains? attackable-players player-id)
                         (concat
                          (->> (get-in player [::player/areas
                                               ::area/battle
                                               ::area/stacks])
                               (filter (fn [{::stack/keys [cards suspended?]
                                            :as stack}]
                                         (and suspended?
                                              (->> (first cards)
                                                   (helpers/card-lookup cards-lookup)
                                                   :card/dp))))))))
                     []))
        attack-actions
        (->> aggressor-stacks
             (mapcat (fn [{::stack/keys [uuid] :as stack}]
                       (concat (map (fn [{attackable-uuid ::stack/uuid}]
                                      [:action/attack.declare
                                       [::player/id current-player-id]
                                       [[::stack/uuid uuid]
                                        [::stack/uuid attackable-uuid]]])
                                    attackable-stacks)
                               (map (fn [id]
                                      [:action/attack.declare
                                       [::player/id current-player-id]
                                       [[::stack/uuid uuid]
                                        [::player/id id]]])
                                    attackable-players)))))
        the-area (concat breeding battle)
        digivolve-actions
        (->> cards-in-hand
             (reduce (fn [accl {::card/keys [uuid] :as card}]
                       (let [{:card/keys [digivolution-requirements]}
                             (helpers/card-lookup cards-lookup card)
                             digivolvable
                             (mapcat
                              (fn [{:digivolve/keys [index cost]
                                   digivolve-color :digivolve/color
                                   digivolve-level :digivolve/level}]
                                (->> the-area
                                     (filter
                                      (comp (fn [{:card/keys [color level]}]
                                              (and (<= cost available-memory)
                                                   (= level digivolve-level)
                                                   (seq (set/intersection
                                                         (->> color
                                                              (map :color/color)
                                                              (into #{}))
                                                         (->> digivolve-color
                                                              (into #{}))))))
                                            (partial helpers/card-lookup
                                                     cards-lookup)
                                            first
                                            ::stack/cards))
                                     (map (fn [{stack-uuid ::stack/uuid}]
                                            [:action/digivolve
                                             [::player/id current-player-id]
                                             [[::card/uuid uuid]
                                              index
                                              [::stack/uuid stack-uuid]]]))))
                              digivolution-requirements)]
                         (cond-> accl
                           (seq digivolvable)
                           (concat digivolvable))))
                     []))]
    (-> game
        (dissoc ::game/attack)
        (assoc ::game/available-actions
               (if (< memory 0)
                 #{[:phase/end-turn [::player/id current-player-id] nil]}
                 (cond-> #{[:action/pass [::player/id current-player-id] nil]}
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
  [{::game/keys [players] {::game-in/keys [turn-index]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [next-turn-index (mod (inc turn-index) (count players))
        {next-player-id ::player/id} (->> next-turn-index
                                          (nth players))]
    (-> game
        (assoc ::game/available-actions
               #{[:phase/start-turn [::player/id next-player-id] nil]})
        (assoc-in [::game/in ::game-in/turn-index] next-turn-index)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/end-turn))))
