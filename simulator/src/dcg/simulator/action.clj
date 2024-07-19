(ns dcg.simulator.action
  (:require
   [clojure.spec.alpha :as s]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.attack :as-alias attack]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]
   [dcg.simulator.random :as random]))

(defn set-memory
  [{::game/keys [db]
    {::game-in/keys [turn]} ::game/in
    :as game} memory]
  (let [memory (min memory 10)
        {next-player-id ::player/id} (helpers/next-player game)]
    (-> game
        (assoc-in [::game/db ::player/id turn ::player/memory]
                  memory)
        (assoc-in [::game/db ::player/id next-player-id ::player/memory]
                  (* memory -1)))))

(defn update-memory
  [{::game/keys [db]
    {::game-in/keys [turn]} ::game/in
    :as game} op value]
  (let [{turn-idx ::player/turn-index} (get-in db [::player/id turn])
        memory (-> (get-in db [::player/id turn ::player/memory])
                   (op value))]
    (set-memory game memory)))

(defn re-draw?
  [{::game/keys [random log players]
    {::game-in/keys [turn]} ::game/in
    :as game} [_ [_ player-id] re-draw? :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (helpers/next-player game)
        {::game/keys [players] :as game}
        (-> game
            (update-in [::game/db
                        ::player/id
                        player-id
                        ::player/areas]
                       (fn [{{deck ::area/cards} ::area/deck
                            {hand ::area/cards} ::area/hand
                            :as areas}]
                         (let [[hand deck] (->> (cond->> (concat hand deck)
                                                  re-draw?
                                                  (random/shuffle random))
                                                (split-at 5)
                                                (map #(into [] %)))
                               [security deck] (->> deck
                                                    (split-at 5)
                                                    (map #(into [] %)))]
                           (-> areas
                               (assoc-in [::area/deck ::area/cards] deck)
                               (assoc-in [::area/hand ::area/cards] hand)
                               (assoc-in [::area/security ::area/cards]
                                         (reverse security))))))
            (assoc ::game/available-actions
                   (let [already-drawn (->> log
                                            (filter (fn [[state-id _ _]]
                                                      (= :action/re-draw?
                                                         state-id)))
                                            (map second)
                                            (into #{}))]
                     (cond-> #{}
                       (not (contains? already-drawn next-player-id))
                       (conj [:action/re-draw?
                              [::player/id next-player-id] true]
                             [:action/re-draw?
                              [::player/id next-player-id] false])))))]
    (cond-> game
      (= (conj (->> log
                    (filter (fn [[state-id _ _]]
                              (= state-id :action/re-draw?)))
                    (map (fn [[_ [_ id] _]]
                           id))
                    (into #{}))
               player-id)
         (->> players
              (map second)
              (into #{})))
      (-> (assoc ::game/available-actions
                 #{[:phase/unsuspend [::player/id turn] nil]})
          (assoc-in [::game/in ::game-in/state-id]
                    :phase/unsuspend)))))

(defn hatch
  [{::game/keys [random db] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [stack-uuid (random/uuid random)]
    (-> game
        (update-in [::game/db ::player/id turn ::player/areas]
                   (fn [areas]
                     (-> areas
                         (update-in [::area/digi-eggs ::area/cards]
                                    (comp #(into [] %) rest))
                         (update-in [::area/breeding ::area/stacks] conj
                                    [::stack/uuid stack-uuid]))))
        (assoc-in [::game/db ::stack/uuid stack-uuid]
                  {::stack/uuid stack-uuid
                   ::stack/cards (->> (get-in db
                                              [::player/id
                                               turn
                                               ::player/areas
                                               ::area/digi-eggs
                                               ::area/cards])
                                      (take 1)
                                      (into []))
                   ::stack/summoned? false
                   ::stack/suspended? false})
        (assoc ::game/available-actions
               #{[:phase/main [::player/id turn] nil]})
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/main))))

(defn move
  [{::game/keys [db] {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      (update-in [::game/db ::player/id turn ::player/areas]
                 (fn [{{breeding ::area/stacks} ::area/breeding :as areas}]
                   (-> areas
                       (update-in [::area/battle
                                   ::area/stacks]
                                  (comp #(into [] %) concat)
                                  breeding)
                       (assoc-in [::area/breeding ::area/stacks] []))))
      (assoc ::game/available-actions
             #{[:phase/main [::player/id turn] nil]})
      (assoc-in [::game/in ::game-in/state-id]
                :phase/main)))

(defn play
  [{::game/keys [random db] {::game-in/keys [turn]} ::game/in
    :as game} [_ _ [card & [override]] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{:card/keys [play-cost use-cost]} (get-in db card)
        stack-uuid (random/uuid random)]
    (-> game
        (update-memory - (or (:cost override)
                             play-cost use-cost))
        (update-in [::game/db ::player/id turn ::player/areas]
                   (fn [{::area/keys [hand battle] :as areas}]
                     (-> areas
                         (update-in [::area/hand ::area/cards]
                                    (fn [cards]
                                      (->> cards
                                           (remove (fn [xcard]
                                                     (= xcard card)))
                                           (into []))))
                         (update-in [::area/battle ::area/stacks] conj
                                    [::stack/uuid stack-uuid]))))
        (assoc-in [::game/db ::stack/uuid stack-uuid]
                  {::stack/uuid stack-uuid
                   ::stack/summoned? true
                   ::stack/suspended? false
                   ::stack/cards [card]})
        (assoc ::game/available-actions
               #{[:phase/main [::player/id turn] nil]}))))

(defn digivolve
  [{::game/keys [db players] {::game-in/keys [turn]} ::game/in
    :as game} [_ _ [card digivolve-idx onto-stack] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{cards-in-hand ::area/cards} ::area/hand
          {deck-cards ::area/cards} ::area/deck} ::player/areas
         :as current-player} (get-in db [::player/id turn])
        deck-has-cards? (pos? (count deck-cards))
        {:card/keys [digivolution-requirements]} (get-in db card)]
    (-> game
        (update-memory - (get-in digivolution-requirements
                                 [digivolve-idx :digivolve/cost]))
        (update-in [::game/db
                    ::player/id
                    turn
                    ::player/areas
                    ::area/hand
                    ::area/cards]
                   (fn [cards]
                     (->> cards
                          (remove (fn [xcard]
                                    (= xcard card)))
                          (into []))))
        (update-in (->> (concat [::game/db] onto-stack)
                        (into []))
                   (fn [stack]
                     (update stack
                             ::stack/cards
                             (fn [cards]
                               (->> cards
                                    (concat [card])
                                    (into []))))))
        ;; Draw on digivolve
        (cond-> #__
          deck-has-cards?
          (-> (update-in [::game/db ::player/id turn ::player/areas
                          ::area/hand ::area/cards]
                         conj
                         (first deck-cards))
              (update-in [::game/db ::player/id turn ::player/areas
                          ::area/deck ::area/cards]
                         (comp #(into [] %) rest))))
        (assoc ::game/available-actions
               #{[:phase/main [::player/id turn] nil]}))))

(defn declare-attack
  [{::game/keys [db]
    {::game-in/keys [turn]} ::game/in
    :as game} [_ _ [attacker target] :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [player-by-stack
        (->> (::player/id db)
             (reduce-kv (fn [accl _ {{{breeding ::area/stacks} ::area/breeding
                                     {battle ::area/stacks} ::area/battle}
                                    ::player/areas
                                    ::player/keys [id]}]
                          (merge accl
                                 (->> (concat breeding battle)
                                      (map (fn [stack]
                                             [stack [::player/id id]]))
                                      (into {}))))
                        {}))
        [_ opponent-id] (case (first target)
                          ::player/id target
                          (get player-by-stack target))]
    (-> game
        (assoc-in [::game/db ::stack/uuid (second attacker) ::stack/suspended?]
                  true)
        (assoc ::game/attack
               {::attack/attacker attacker
                ::attack/attacking target
                ::attack/player [::player/id opponent-id]}
               ::game/available-actions
               #{[:action/attack.counter
                  [::player/id opponent-id]
                  :require-input]})))
  ;; TODO: [When Attacking] and "when a Digimon attacks" effects trigger
  )

(defn counter-attack
  [{::game/keys [players]
    {::attack/keys [attacking player]} ::game/attack
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      (assoc ::game/available-actions
             (cond-> #{(if (= (first attacking)
                              ::player/id)
                         [:action/attack.security player nil]
                         [:action/attack.digimon player nil])}
               ;; TODO: enumerate all possible blocks
               #_(conj [:action/attack.block player stack-uuid])
               )))
  ;; TODO: Opponent's "When an opponent's Digimon attacks" effects activate.
  )

(defn block-attack
  [{::game/keys [players]
    {::game-in/keys [turn]} ::game/in
    :as game} [_ _ target :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (-> game
      (assoc-in [::game/attack ::attack/attacking] target)
      (assoc ::game/available-actions
             #{[:action/attack.digimon [::player/id turn] nil]})))

(defn digimon-attack
  [{::game/keys [db players]
    {::attack/keys [attacker attacking player]} ::game/attack
    {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{battle-stacks ::area/stacks} ::area/battle}
         ::player/areas} (get-in db [::player/id turn])
        attacker-stack (get-in db attacker)
        attacker-dp (or (-> attacker-stack ::stack/dp)
                        (-> (get-in db (-> attacker-stack
                                           ::stack/cards
                                           first))
                            (get :card/dp 0)))
        {{{opponent-battle-stacks ::area/stacks} ::area/battle}
         ::player/areas} (get-in db player)
        attacking-stack (get-in db attacking)
        attacking-dp (or (-> attacking-stack ::stack/dp)
                         (-> (get-in db (-> attacking-stack
                                            ::stack/cards
                                            first))
                             (get :card/dp 0)))]
    (cond-> game
      (<= attacker-dp attacking-dp)
      ;; TODO: On deletion effect
      (-> (update-in [::game/db ::stack/uuid] dissoc (second attacker))
          (update-in [::game/db ::player/id turn
                      ::player/areas ::area/battle ::area/stacks]
                     (fn [stacks]
                       (->> stacks
                            (remove (fn [stack]
                                      (= stack attacker)))
                            (into []))))
          (update-in [::game/db ::player/id turn
                      ::player/areas ::area/trash ::area/cards]
                     (fn [cards]
                       (->> (concat (::stack/cards attacker-stack)
                                    cards)
                            (into [])))))
      (>= attacker-dp attacking-dp)
      ;; TODO: On deletion effect
      (-> (update-in [::game/db ::stack/uuid] dissoc (second attacking))
          (update-in [::game/db ::player/id (second player)
                      ::player/areas ::area/battle ::area/stacks]
                     (fn [stacks]
                       (->> stacks
                            (remove (fn [stack]
                                      (= stack attacking)))
                            (into []))))
          (update-in [::game/db ::player/id (second player)
                      ::player/areas ::area/trash ::area/cards]
                     (fn [cards]
                       (->> (concat (::stack/cards attacking-stack)
                                    cards)
                            (into [])))))
      true
      (assoc ::game/available-actions
             (if false ;; TODO: if attacker has piercing and survived
               #{[:action/attack.security [::player/id turn] nil]}
               #{[:phase/main [::player/id turn] nil]})))))

(defn security-attack
  [{::game/keys [db players]
    {::attack/keys [attacker attacking player]} ::game/attack
    {::game-in/keys [turn]} ::game/in
    :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{{{battle-stacks ::area/stacks} ::area/battle}
         ::player/areas} (get-in db [::player/id turn])
        attacker-stack (get-in db attacker)
        attacker-dp (or (-> attacker-stack ::stack/dp)
                        (-> (get-in db (-> attacker-stack
                                           ::stack/cards
                                           first))
                            (get :card/dp 0)))
        {{{opponent-security ::area/cards} ::area/security}
         ::player/areas} (get-in db player)
        attacking-card (first opponent-security)
        attacking-dp (-> (get-in db attacking-card)
                         (get :card/dp 0))]
    (if (zero? (count opponent-security))
      (-> game
          (assoc ::game/available-actions #{})
          (assoc-in [::game/in ::game-in/state-id] :game/end)
          (update-in [::game/log] conj
                     [:game/end [::player/id turn] [::player/id turn]]))
      (cond-> game
        (<= attacker-dp attacking-dp)
        ;; TODO: On deletion effect
        (-> (update-in [::game/db ::stack/uuid] dissoc (second attacker))
            (update-in [::game/db ::player/id turn
                        ::player/areas ::area/battle ::area/stacks]
                       (fn [stacks]
                         (->> stacks
                              (remove (fn [stack]
                                        (= stack attacker)))
                              (into []))))
            (update-in [::game/db ::player/id turn
                        ::player/areas ::area/trash ::area/cards]
                       (fn [cards]
                         (->> (concat (::stack/cards attacker-stack)
                                      cards)
                              (into [])))))
        true
        (-> (update-in [::game/db ::player/id (second player)
                        ::player/areas ::area/security ::area/cards]
                       (comp #(into [] %) rest))
            ;; TODO: security effect may move to battle area instead of trash
            (update-in [::game/db ::player/id (second player)
                        ::player/areas ::area/trash ::area/cards]
                       (fn [cards]
                         (->> (concat [attacking-card]
                                      cards)
                              (into [])))))
        true
        (assoc ::game/available-actions
               (if false ;; TODO: if attacker has security attack +N
                 #{[:action/attack.security turn nil]}
                 #{[:phase/main [::player/id turn] nil]}))))))

(defn pass
  [{{::game-in/keys [turn]} ::game/in :as game} action]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (let [{next-player-id ::player/id} (helpers/next-player game)]
    (-> game
        (set-memory -3)
        (assoc ::game/available-actions
               #{[:phase/unsuspend [::player/id turn] nil]})
        (assoc-in [::game/in ::game-in/turn] next-player-id)
        (assoc-in [::game/in ::game-in/state-id]
                  :phase/unsuspend))))
