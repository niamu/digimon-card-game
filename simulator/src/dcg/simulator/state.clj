(ns dcg.simulator.state
  (:require
   [clojure.spec.alpha :as s]
   [dcg.codec.decode :as codec-decode]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.action :as action]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.phase :as phase]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack])
  (:import
   [java.time Instant]))

(defonce state
  (atom {::queue clojure.lang.PersistentQueue/EMPTY
         ::games-by-id {}
         ::game-by-player-id {}}))

(defn player-in-queue?
  [{::player/keys [id] :as player}]
  (contains? (->> @state
                  ::queue
                  (map ::player/id)
                  (into #{}))
             id))

(defn player-in-game?
  [{::player/keys [id] :as player}]
  (when-let [game-id (get-in @state [::game-by-player-id id])]
    (let [game-state (get-in @state [::games-by-id
                                     game-id
                                     ::game/in
                                     ::game-in/state-id])]
      (when (not= :game/end game-state)
        game-id))))

(defn- decoded->deck
  [cards language]
  (reduce (fn [accl {:card/keys [number parallel-id count]
                    :or {parallel-id 0}}]
            (apply conj accl
                   (repeatedly count
                               (fn []
                                 {::card/uuid (random-uuid)
                                  ::card/number number
                                  ::card/parallel-id parallel-id
                                  ::card/lookup [::game/db
                                                 language
                                                 [number parallel-id]]}))))
          []
          cards))

(defn private-state-for-player-id
  [{::game/keys [players db]
    {::game-in/keys [turn]} ::game/in
    :as game} player-id]
  {:pre [(s/valid? ::simulator/game game)
         (s/valid? ::player/id player-id)]}
  (let [player-language (get-in (helpers/players-by-id players)
                                [player-id ::player/language]
                                "en")
        update-lookup-fn (fn [{::card/keys [lookup] :as card}]
                           (assoc card
                                  ::card/lookup
                                  (assoc lookup 1 player-language)))
        visible-cards
        (->> players
             (mapcat (fn [{::player/keys [id areas]}]
                       (reduce-kv
                        (fn [accl area {::area/keys [privacy cards stacks]}]
                          (if stacks
                            (concat accl
                                    (->> stacks
                                         (mapcat ::stack/cards)
                                         (filter
                                          (fn [card]
                                            (let [privacy
                                                  (get card
                                                       ::card/privacy
                                                       privacy)]
                                              (or (= privacy :public)
                                                  (and (= privacy :owner)
                                                       (= id player-id))))))))
                            (concat accl
                                    (->> cards
                                         (filter
                                          (fn [card]
                                            (let [privacy
                                                  (get card
                                                       ::card/privacy
                                                       privacy)]
                                              (or (= privacy :public)
                                                  (and (= privacy :owner)
                                                       (= id player-id))))))))))
                        []
                        areas)))
             (map update-lookup-fn))
        uuid->lookup (->> visible-cards
                          (reduce (fn [accl {::card/keys [uuid lookup]}]
                                    (assoc accl
                                           uuid
                                           lookup))
                                  {}))
        update-cards-fn (fn [cards]
                          (map (fn [{::card/keys [uuid] :as card}]
                                 (when (get uuid->lookup uuid)
                                   (update-lookup-fn card)))
                               cards))
        players
        (->> players
             (mapv (fn [{::player/keys [id] :as player}]
                     (-> player
                         (update ::player/areas
                                 (fn [areas]
                                   (reduce-kv
                                    (fn [accl k {::area/keys [cards stacks]
                                                :as area}]
                                      (assoc accl k
                                             (cond-> area
                                               cards
                                               (update ::area/cards
                                                       update-cards-fn)
                                               stacks
                                               (update ::area/stacks
                                                       (fn [stacks]
                                                         (map
                                                          (fn [{::stack/keys [cards]
                                                               :as stack}]
                                                            (update stack
                                                                    ::stack/cards
                                                                    update-cards-fn))
                                                          stacks))))))
                                    {}
                                    areas)))
                         (cond-> #__
                           (= id player-id) (assoc ::player/uuid->lookup
                                                   uuid->lookup))))))]
    (-> game
        (dissoc ::game/instant
                ::game/seed)
        (update ::game/available-actions
                (fn [actions]
                  (reduce (fn [accl [_ id _ :as action]]
                            (cond-> accl
                              (= id player-id)
                              (conj action)))
                          #{}
                          actions)))
        (assoc ::game/players players)
        (assoc ::game/db (->> visible-cards
                              (reduce (fn [accl {::card/keys [lookup]}]
                                        (assoc-in accl
                                                  (into [] (rest lookup))
                                                  (get-in game lookup)))
                                      {}))))))

(defn initialize-player
  [seed {::player/keys [deck-code id] :as player}]
  (let [{:deck/keys [digi-eggs deck language]
         :or {language "en"}} (codec-decode/decode deck-code)
        initial-digi-eggs (->> (decoded->deck digi-eggs language)
                               (helpers/shuffle-with-seed [seed id]))
        [initial-hand initial-deck] (->> (decoded->deck deck language)
                                         (helpers/shuffle-with-seed [seed id])
                                         (split-at 5)
                                         (map #(into [] %)))
        cards (concat initial-digi-eggs
                      initial-hand
                      initial-deck)]
    (-> player
        (assoc ::player/id (or id (random-uuid))
               ::player/language language
               ::player/memory 0
               ::player/areas {::area/digi-eggs
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
                                ::area/cards initial-hand}}
               ::player/timings #{}))))

(defn initialize
  [players constraint-code]
  {:pre [(s/valid? (s/coll-of (s/keys :req [::player/name
                                            ::player/deck-code])
                              :distinct true
                              :min-count 2) players)
         (s/valid? ::game/constraint-code constraint-code)]
   :post [(s/valid? ::simulator/game %)]}
  (let [game-id (random-uuid)
        instant (Instant/now)
        players (->> players
                     (helpers/shuffle-with-seed game-id)
                     (mapv (partial initialize-player game-id)))
        turn (-> players first ::player/id)
        db (helpers/load-cards (->> players
                                    (mapcat
                                     (comp
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
     ::game/instant instant
     ::game/turn-counter 0
     ::game/constraint-code constraint-code
     ::game/log [[:phase/pre-game game-id "v0.0.0"]]
     ::game/pending-effects clojure.lang.PersistentQueue/EMPTY
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
                   (update ::game-by-player-id merge
                           (reduce (fn [accl {::player/keys [id]}]
                                     (assoc accl id (::game/id game)))
                                   {}
                                   players))))))))

(def fsm
  {;; Actions
   :action/re-draw? #'action/re-draw?
   :action/hatch #'action/hatch
   :action/move #'action/move
   :action/use #'action/play
   :action/play #'action/play
   :action/digivolve #'action/digivolve
   :action/pass #'action/pass
   ;; Phases
   :phase/unsuspend #'phase/unsuspend
   :phase/draw #'phase/draw
   :phase/breeding #'phase/breeding
   :phase/main #'phase/main
   :phase/end-turn #'phase/end-of-turn})

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
        {::game/keys [available-actions in]
         :as game} (-> game
                       (handler action)
                       (as-> #__ game
                         (cond-> game
                           (not= (get-in game [::game/in ::game-in/state-id])
                                 :game/end)
                           (-> (update ::game/available-actions disj action)
                               (update ::game/log conj action)))))]
    (cond-> game
      (= (count available-actions) 1)
      (flow (first available-actions)))))

(comment
  (let [game (initialize [{::player/name "niamu"
                           ::player/deck-code "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
                          {::player/name "AI"
                           ::player/deck-code "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}] nil)
        player-by-turn-idx (->> (get-in game [::game/players])
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
        (private-state-for-player-id (get player-by-turn-idx 1))
        #_(get-in [::game/db])
        #_(get-in [::game/players 0 ::player/areas])
        (get-in [::game/players 1 ::player/uuid->lookup])
        #_(get-in [::game/players 1 ::player/memory])
        ;; private player views
        #_(private-state-for-player-id (get player-by-turn-idx 0))))

  )
