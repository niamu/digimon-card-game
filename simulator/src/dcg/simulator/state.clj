(ns dcg.simulator.state
  (:require
   [clojure.spec.alpha :as s]
   [datomic.api :as d]
   [dcg.codec.decode :as codec-decode]
   [dcg.db :as db]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]))

(d/q '{:find [[(pull ?c
                     [:card/id
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
                      :card/block-marker
                      :card/notes
                      {:card/color [:color/index
                                    :color/color]}
                      {:card/digivolve-conditions [:digivolve/index
                                                   :digivolve/color
                                                   :digivolve/cost]}
                      {:card/releases [:release/name
                                       :release/genre
                                       :release/date]}
                      {:card/image [:image/path]}
                      {:card/highlights [:highlight/index
                                         :highlight/field
                                         :highlight/type
                                         :highlight/text]}
                      {:card/errata [*]}
                      :card/effect
                      :card/inherited-effect
                      :card/security-effect]) ...]]
       :in [$]
       :where [[?c :card/image ?i]
               [?c :card/parallel-id ?p]
               [?c :card/number "BT10-086"]
               [?c :card/language "en"]
               [?i :image/language ?l]]}
     (d/db db/conn))

(defonce state
  (atom {::queue clojure.lang.PersistentQueue/EMPTY
         ::games-by-id {}
         ::games-by-player {}}))

(defn player-in-queue?
  [{::player/keys [id] :as player}]
  (contains? (->> @state
                  ::queue
                  (map ::player/id)
                  (into #{}))
             id))

(defn player-in-game?
  [{::player/keys [id] :as player}]
  (get-in @state [::games-by-player id]))

(defn load-cards
  [card-ids languages]
  (->> (d/q '{:find [[(pull ?c
                            [:card/id
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
                             :card/block-marker
                             :card/notes
                             {:card/color [:color/index
                                           :color/color]}
                             {:card/digivolve-conditions [:digivolve/index
                                                          :digivolve/color
                                                          :digivolve/cost]}
                             {:card/releases [:release/name
                                              :release/genre
                                              :release/date]}
                             {:card/image [:image/path]}
                             {:card/highlights [:highlight/index
                                                :highlight/field
                                                :highlight/type
                                                :highlight/text]}
                             :card/effect
                             :card/inherited-effect
                             :card/security-effect]) ...]]
              :in [$ [[?n ?p] ...] [?l ...]]
              :where [[?c :card/image ?i]
                      [?c :card/parallel-id ?p]
                      [?c :card/number ?n]
                      [?c :card/language ?l]
                      [?i :image/language ?l]]}
            (d/db db/conn)
            card-ids
            languages)
       (reduce (fn [accl {:card/keys [language number parallel-id] :as card}]
                 (assoc-in accl
                           [language [number parallel-id]]
                           card))
               {})))

(defn- decoded->deck
  [d]
  (reduce (fn [accl {:card/keys [number parallel-id count]
                    :or {parallel-id 0}}]
            (apply conj accl
                   (repeatedly count
                               (fn []
                                 {::card/uuid (random-uuid)
                                  ::card/number number
                                  ::card/parallel-id parallel-id
                                  ::card/lookup [::game/db
                                                 "en"
                                                 [number parallel-id]]}))))
          []
          d))

(defn next-turn
  [{::game/keys [players]
    {::game-in/keys [turn]} ::game/in}]
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))]
    (get-in players
            [(-> (inc turn-idx)
                 (mod (count players)))
             ::player/id])))

(defn private-state-for-player-id
  [{::game/keys [players]
    {::game-in/keys [turn]} ::game/in
    :as game} player-id]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::player/id player-id)]}
  (let [players
        (->> players
             (mapv (fn [{::player/keys [id] :as player}]
                     (update player
                             ::player/areas
                             (fn [areas]
                               (reduce-kv
                                (fn [accl k
                                    {::area/keys [privacy]
                                     :as area}]
                                  (assoc accl k
                                         (cond-> area
                                           (and (::area/cards area)
                                                (or (= privacy :private)
                                                    (and (= privacy :owner)
                                                         (not= id player-id))))
                                           (update ::area/cards count))))
                                {}
                                areas))))))]
    (-> game
        (update ::game/available-actions
                (fn [actions]
                  (reduce (fn [accl [_ id _ :as action]]
                            (cond-> accl
                              (= id player-id)
                              (conj action)))
                          #{}
                          actions)))
        (assoc ::game/players players)
        (update ::game/db
                (fn [db]
                  (->> players
                       (mapcat
                        (comp
                         (fn [areas]
                           (reduce-kv
                            (fn [accl area
                                {::area/keys [cards stacks]}]
                              (if (coll? (or cards stacks))
                                (if stacks
                                  (concat accl
                                          (->> stacks
                                               (mapcat (comp
                                                        (fn [cards]
                                                          (map ::card/lookup
                                                               cards))
                                                        ::stack/cards))))
                                  (concat accl
                                          (->> cards
                                               (map ::card/lookup))))
                                accl))
                            []
                            areas))
                         ::player/areas))
                       (reduce (fn [accl lookup]
                                 (assoc-in accl
                                           (into [] (rest lookup))
                                           (get-in game lookup)))
                               {})))))))

(defn initialize-player
  [{::player/keys [deck-code id] :as player}]
  (let [{:deck/keys [digi-eggs deck language]
         :or {language "en"}} (codec-decode/decode deck-code)
        initial-digi-eggs (->> (decoded->deck digi-eggs)
                               shuffle)
        [initial-hand initial-deck] (->> (decoded->deck deck)
                                         shuffle
                                         (split-at 5)
                                         (map #(into [] %)))]
    (-> player
        (assoc ::player/id (or id (random-uuid))
               ::player/language language
               ::player/memory 0
               ::player/areas
               {::area/digi-eggs
                {::area/privacy :private
                 ::area/cards initial-digi-eggs}
                ::area/deck
                {::area/privacy :private
                 ::area/cards initial-deck}
                ::area/breeding
                {::area/privacy :public
                 ::area/stacks []}
                ::area/trash
                {::area/privacy :public
                 ::area/cards []}
                ::area/battle
                {::area/privacy :public
                 ::area/stacks []}
                ::area/security
                {::area/privacy :private
                 ::area/cards []}
                ::area/hand
                {::area/privacy :owner
                 ::area/cards initial-hand}}))))

(defn initialize
  [players constraint-code]
  {:pre [(s/valid? (s/coll-of (s/keys :req [::player/name
                                            ::player/deck-code])
                              :distinct true
                              :min-count 2) players)
         (s/valid? ::game/constraint-code constraint-code)]
   :post [(s/valid? ::simulator/game %)]}
  (let [players (->> players
                     shuffle
                     (mapv initialize-player))
        turn (-> players first ::player/id)
        game-id (random-uuid)
        db (load-cards (->> players
                            (mapcat (comp
                                     (fn [areas]
                                       (mapcat (fn [[_ {cards ::area/cards}]]
                                                 cards)
                                               areas))
                                     ::player/areas))
                            (map (juxt ::card/number
                                       ::card/parallel-id)))
                       (->> players
                            (map ::player/language)
                            (reduce conj #{"en"})))]
    {::game/id game-id
     ::game/turn-counter nil
     ::game/constraint-code constraint-code
     ::game/log [[:phase/pre-game game-id "v0.0.0"]]
     ::game/effect-queue clojure.lang.PersistentQueue/EMPTY
     ::game/available-actions #{[:action/re-draw? turn true]
                                [:action/re-draw? turn false]}
     ::game/in {::game-in/turn turn
                ::game-in/state-id :phase/pre-game}
     ::game/players players
     ::game/db db}))

(defn initialize-from-queue!
  []
  (when (> (count (get @state ::queue)) 1)
    (let [players (take 2 (get @state ::queue))
          game (initialize players nil)]
      (swap! state
             (fn [state]
               (-> state
                   (update ::queue (fn [q] (nth (iterate pop q) 2)))
                   (assoc-in [::games-by-id (::game/id game)] game)
                   (update ::games-by-player merge
                           (reduce (fn [accl {::player/keys [id]}]
                                     (assoc accl id (::game/id game)))
                                   {}
                                   players))))))))

(defn players-by-id
  [players]
  (reduce (fn [accl {::player/keys [id] :as player}]
            (assoc accl id player))
          {}
          players))

(defn update-memory
  [{::game/keys [players] {::game-in/keys [turn]} ::game/in :as game} op value]
  (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                               (assoc accl id (count accl)))
                             {}
                             players)
                     (get turn))
        next-turn-idx (-> turn-idx
                          inc
                          (mod (count players)))
        memory (-> (get-in game [::game/players turn-idx ::player/memory])
                   (op value))]
    (-> game
        (assoc-in [::game/players turn-idx ::player/memory]
                  memory)
        (assoc-in [::game/players next-turn-idx ::player/memory]
                  (* memory -1)))))

(def fsm
  {:action/re-draw?
   (fn [{::game/keys [log players] :as game}
       [_ player-id re-draw? :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [{::game/keys [log players]
            {::game-in/keys [turn]} ::game/in
            :as game}
           (-> game
               (update ::game/players
                       (fn [players]
                         (mapv (fn [{::player/keys [id] :as player}]
                                 (cond-> player
                                   (= id player-id)
                                   (update ::player/areas
                                           (fn [{::area/keys [deck hand]
                                                :as areas}]
                                             (let [[new-hand new-deck]
                                                   (->> (concat
                                                         (::area/cards deck)
                                                         (::area/cards hand))
                                                        shuffle
                                                        (split-at 5)
                                                        (map #(into [] %)))
                                                   [security new-deck]
                                                   (->> (if re-draw?
                                                          new-deck
                                                          (::area/cards deck))
                                                        (split-at 5)
                                                        (map #(into [] %)))
                                                   security (->> security
                                                                 reverse)]
                                               (cond-> (-> areas
                                                           (assoc-in
                                                            [::area/deck
                                                             ::area/cards]
                                                            new-deck)
                                                           (update-in
                                                            [::area/security
                                                             ::area/cards]
                                                            (fn [q]
                                                              (->> security
                                                                   (reduce conj
                                                                           q)))))
                                                 re-draw?
                                                 (assoc-in [::area/hand
                                                            ::area/cards]
                                                           new-hand)))))))
                               players)))
               (assoc ::game/available-actions
                      (let [already-drawn (->> log
                                               (filter (fn [[state-id _ _]]
                                                         (= :action/re-draw?
                                                            state-id)))
                                               (map second)
                                               (into #{}))
                            next-player-id (next-turn game)]
                        (cond-> #{}
                          (not (contains? already-drawn next-player-id))
                          (conj [:action/re-draw? next-player-id true]
                                [:action/re-draw? next-player-id false])))))]
       (cond-> game
         (= (conj (->> log
                       (filter (fn [[state-id _ _]]
                                 (= state-id :action/re-draw?)))
                       (map second)
                       (into #{}))
                  player-id)
            (->> players
                 (map ::player/id)
                 (into #{})))
         (-> (assoc ::game/available-actions
                    #{[:timing/start-of-turn turn nil]})
             (assoc-in [::game/in ::game-in/state-id]
                       :phase/unsuspend)))))

   :timing/start-of-turn
   (fn [{{::game-in/keys [turn]} ::game/in :as game} action]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (-> game
         (update ::game/turn-counter (fnil inc 0))
         (update ::game/available-actions
                 conj
                 [:phase/unsuspend turn nil])
         (assoc-in [::game/in ::game-in/state-id]
                   :phase/unsuspend)))

   :phase/unsuspend
   (fn [{::game/keys [log players] {::game-in/keys [turn]} ::game/in
        :as game} [state-id player-id _ :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (-> game
         (update ::game/players
                 (fn [players]
                   (mapv (fn [{::player/keys [id] :as player}]
                           (if (= id turn)
                             (-> player
                                 (update-in [::player/areas
                                             ::area/battle
                                             ::area/stacks]
                                            (fn [stacks]
                                              (mapv (fn [stack]
                                                      (assoc stack
                                                             ::stack/suspended?
                                                             false))
                                                    stacks))))
                             player))
                         players)))
         (update ::game/available-actions
                 conj
                 [:phase/draw turn nil])
         (assoc-in [::game/in ::game-in/state-id]
                   :phase/draw)))

   :phase/draw
   (fn [{::game/keys [turn-counter players] {::game-in/keys [turn]} ::game/in
        :as game} [state-id player-id _ :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (cond-> game
       (not (and (= turn-counter 1)
                 (= turn (-> players first ::player/id))))
       (update ::game/players
               (fn [players]
                 (mapv (fn [{::player/keys [id] :as player}]
                         (cond-> player
                           (= id turn)
                           (update-in [::player/areas]
                                      (fn [{::area/keys [hand deck] :as areas}]
                                        (-> areas
                                            (update-in [::area/deck
                                                        ::area/cards]
                                                       (comp #(into [] %)
                                                             rest))
                                            (update-in [::area/hand
                                                        ::area/cards]
                                                       conj
                                                       (->> deck
                                                            ::area/cards
                                                            first)))))))
                       players)))
       true
       (-> (assoc ::game/available-actions
                  #{[:phase/breeding turn nil]})
           (assoc-in [::game/in ::game-in/state-id]
                     :phase/breeding))))

   :phase/breeding
   (fn [{::game/keys [players] {::game-in/keys [turn]} ::game/in
        :as game} [state-id player-id _ :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                  (assoc accl id (count accl)))
                                {}
                                players)
                        (get turn))]
       (assoc game
              ::game/available-actions
              (cond-> #{[:phase/main turn nil]}
                (and (empty? (get-in (players-by-id players)
                                     [turn
                                      ::player/areas
                                      ::area/breeding
                                      ::area/stacks]))
                     (seq (get-in (players-by-id players)
                                  [turn
                                   ::player/areas
                                   ::area/digi-eggs
                                   ::area/cards])))
                (conj [:action/hatch turn nil])
                (when-let [stack (seq (get-in (players-by-id players)
                                              [turn
                                               ::player/areas
                                               ::area/breeding
                                               ::area/stacks]))]
                  (some-> stack ::stack/cards last :card/dp))
                (conj [:action/move turn nil])))))

   :action/hatch
   (fn [{::game/keys [players] {::game-in/keys [turn]} ::game/in
        :as game} [state-id player-id hatch-or-move :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                  (assoc accl id (count accl)))
                                {}
                                players)
                        (get turn))]
       (-> game
           (update-in [::game/players turn-idx ::player/areas]
                      (fn [areas]
                        (-> areas
                            (update-in [::area/digi-eggs
                                        ::area/cards]
                                       (comp #(into [] %)
                                             rest))
                            (update-in [::area/breeding
                                        ::area/stacks]
                                       conj
                                       {::stack/uuid (random-uuid)
                                        ::stack/cards
                                        (->> (get-in areas
                                                     [::area/digi-eggs
                                                      ::area/cards])
                                             (take 1)
                                             (into []))}))))
           (assoc ::game/available-actions
                  #{[:phase/main turn nil]})
           (assoc-in [::game/in ::game-in/state-id]
                     :phase/main))))

   :phase/main
   (fn [{::game/keys [players] {::game-in/keys [turn]} ::game/in
        :as game} action]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                  (assoc accl id (count accl)))
                                {}
                                players)
                        (get turn))
           {::player/keys [memory]
            {{cards-in-hand ::area/cards} ::area/hand} ::player/areas
            :as player} (get-in game [::game/players turn-idx])
           available-memory (+ memory 10)
           playable-cards
           (->> cards-in-hand
                (filter (fn [{::card/keys [lookup uuid]}]
                          (let [{:card/keys [play-cost]} (get-in game lookup)]
                            (when play-cost
                              (<= play-cost available-memory)))))
                (map (fn [{::card/keys [uuid]}]
                       [:action/play turn uuid])))
           usable-cards
           (->> cards-in-hand
                (filter (fn [{::card/keys [lookup uuid]}]
                          (let [{:card/keys [use-cost]} (get-in game lookup)]
                            (when use-cost
                              (<= use-cost available-memory)))))
                (map (fn [{::card/keys [uuid]}]
                       [:action/use turn uuid])))]
       (-> game
           (assoc ::game/available-actions
                  (if (< memory 0)
                    #{[:phase/end-turn turn nil]}
                    (cond-> #{[:action/pass turn nil]}
                      (seq playable-cards)
                      (as-> #__ available-actions
                        (apply conj available-actions playable-cards))
                      (seq usable-cards)
                      (as-> #__ available-actions
                        (apply conj available-actions usable-cards))
                      ;; TODO
                      ;; - Digivolve a Digimon
                      ;; - Attack
                      ;; - Activate a [Main] timing effect
                      )))
           (assoc-in [::game/in ::game-in/state-id]
                     :phase/main))))

   :action/use
   (fn [{::game/keys [players] {::game-in/keys [turn]} ::game/in
        :as game} [_ _ card-uuid :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                  (assoc accl id (count accl)))
                                {}
                                players)
                        (get turn))]
       (let [card (->> (get-in game
                               [::game/players turn-idx ::player/areas
                                ::area/hand ::area/cards])
                       (filter (fn [{::card/keys [uuid]}]
                                 (= uuid card-uuid)))
                       first)
             {:card/keys [use-cost]} (get-in game (::card/lookup card))]
         (-> game
             (update-memory - use-cost)
             (update-in [::game/players turn-idx ::player/areas]
                        (fn [{::area/keys [hand battle] :as areas}]
                          (-> areas
                              (update-in [::area/hand ::area/cards]
                                         (fn [cards]
                                           (->> cards
                                                (remove (fn [{::card/keys [uuid]}]
                                                          (= uuid card-uuid)))
                                                (into []))))
                              (update-in [::area/battle ::area/stacks]
                                         conj
                                         {::stack/uuid (random-uuid)
                                          ::stack/cards [card]}))))
             (assoc ::game/available-actions
                    #{[:phase/main turn nil]})))))

   :action/play
   (fn [{::game/keys [players] {::game-in/keys [turn]} ::game/in
        :as game} [_ _ card-uuid :as action]]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                  (assoc accl id (count accl)))
                                {}
                                players)
                        (get turn))]
       (let [card (->> (get-in game
                               [::game/players turn-idx ::player/areas
                                ::area/hand ::area/cards])
                       (filter (fn [{::card/keys [uuid]}]
                                 (= uuid card-uuid)))
                       first)
             {:card/keys [play-cost]} (get-in game (::card/lookup card))]
         (-> game
             (update-memory - play-cost)
             (update-in [::game/players turn-idx ::player/areas]
                        (fn [{::area/keys [hand battle] :as areas}]
                          (-> areas
                              (update-in [::area/hand ::area/cards]
                                         (fn [cards]
                                           (->> cards
                                                (remove (fn [{::card/keys [uuid]}]
                                                          (= uuid card-uuid)))
                                                (into []))))
                              (update-in [::area/battle ::area/stacks]
                                         conj
                                         {::stack/uuid (random-uuid)
                                          ::stack/cards [card]}))))
             (assoc ::game/available-actions
                    #{[:phase/main turn nil]})))))

   :phase/end-turn
   (fn [{::game/keys [players] {::game-in/keys [turn]} ::game/in
        :as game} action]
     {:pre [(s/valid? ::simulator/game game)
            (s/valid? ::simulator/action action)]
      :post [(s/valid? ::simulator/game %)]}
     (let [turn-idx (-> (reduce (fn [accl {::player/keys [id]}]
                                  (assoc accl id (count accl)))
                                {}
                                players)
                        (get turn)
                        inc
                        (mod (count players)))
           {::player/keys [id]} (get-in game [::game/players turn-idx])]
       (-> game
           (assoc ::game/available-actions
                  #{[:phase/unsuspend id nil]})
           (assoc-in [::game/in ::game-in/turn]
                     id)
           (assoc-in [::game/in ::game-in/state-id]
                     :phase/end-turn))))})

(defn flow
  [{::game/keys [available-actions log players]
    {::game-in/keys [turn state-id]} ::game/in
    :as game} [action-state-id _ _ :as action]]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::simulator/action action)]
   :post [(s/valid? ::simulator/game %)]}
  (when-not (contains? available-actions action)
    (throw (Exception. (str "Invalid action: " action))))
  (let [handler (get-in fsm [action-state-id])
        {::game/keys [available-actions]
         :as game} (-> game
                       (handler action)
                       (update ::game/available-actions disj action)
                       (update ::game/log conj action))]
    (cond-> game
      (= (count available-actions) 1)
      (flow (first available-actions)))))

(comment
  (db/import-from-file!)

  (defonce game
    (initialize [{::player/name "niamu"
                  ::player/deck-code "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
                 {::player/name "AI"
                  ::player/deck-code "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}] nil))

  (let [player-by-turn-idx (->> (get-in game [::game/players])
                                (reduce (fn [accl {::player/keys [id]}]
                                          (assoc accl (count accl) id))
                                        {}))]
    (-> game
        (flow [:action/re-draw? (get player-by-turn-idx 0) false])
        (flow [:action/re-draw? (get player-by-turn-idx 1) true])
        (flow [:action/hatch (get player-by-turn-idx 0) nil])
        #_(flow [:action/use
                 (get player-by-turn-idx 0)
                 #uuid "4a1509d5-ef0a-4a08-ad1d-2f3b0f5043b5"])
        #_(flow [:action/hatch (get player-by-turn-idx 1) nil])
        #_(flow [:action/play
                 (get player-by-turn-idx 1)
                 #uuid "43f173f0-3a55-4e9c-b774-390e21a9fbb3"])

        ;; examine game state
        #_(get-in [::game/available-actions])
        #_(get-in [::game/players 1 ::player/memory])
        (get-in [::game/players 0 ::player/areas ::area/breeding])
        ;; private player views
        #_(private-state-for-player-id (get player-by-turn-idx 0))))

  )
