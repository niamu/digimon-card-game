(ns dcg.simulator.server.query
  (:require
   [clojure.spec.alpha :as s]
   [com.wsscode.pathom.core :as p]
   [com.wsscode.pathom.connect :as pc]
   [dcg.db.db :as db]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.area :as-alias area]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.game.in :as-alias game-in]
   [dcg.simulator.helpers :as helpers]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.stack :as-alias stack]
   [dcg.simulator.state :as state])
  (:import
   [java.util UUID]))

(pc/defresolver current-user
  [{{{current-player-id ::player/id} :player} :session
    :as request} {::game/keys [players]}]
  {::pc/input #{::game/players}
   ::pc/output [:current/user]}
  {:current/user (get (helpers/players-by-id players)
                      current-player-id)})

(pc/defresolver area
  [{{{current-player-id ::player/id} :player} :session
    {:keys [game-id]} :path-params
    :as request} {::area/keys [of-player privacy] :as area
                  area-name ::area/name
                  {::player/keys [language]} :current/user}]
  {::pc/input #{::area/name
                ::area/privacy
                ::area/of-player
                :current/user}
   ::pc/output [::area/actions
                ::area/cards
                ::area/stacks]}
  (let [{::game/keys [available-actions players cards-lookup]
         :as game} (get-in @state/state [::state/games-by-id
                                         (UUID/fromString game-id)])
        {::area/keys [cards stacks]} (get-in (helpers/players-by-id players)
                                             [of-player
                                              ::player/areas
                                              (case area-name
                                                ::area/tamer-and-option
                                                ::area/battle
                                                area-name)])
        visible? (or (= privacy :public)
                     (and (= of-player current-player-id)
                          (= privacy :owner)))
        add-card-info (fn [{::card/keys [uuid] :as card}]
                        (cond-> card
                          visible?
                          (assoc ::card/card
                                 (get-in cards-lookup
                                         [uuid (or language "en")]))))
        tamer-and-option (fn [{::stack/keys [cards]}]
                           (let [{{:card/keys [category]}
                                  ::card/card} (last cards)]
                             (contains? #{"Tamer"
                                          "テイマー"
                                          "테이머"
                                          "驯兽师"
                                          "Option"
                                          "オプション"
                                          "옵션"
                                          "选项"}
                                        category)))]
    (cond-> (if cards
              {::area/cards (map add-card-info cards)}
              {::area/stacks (map (fn [stack]
                                    (update stack
                                            ::stack/cards
                                            (fn [cards]
                                              (map add-card-info cards))))
                                  stacks)})
      (= area-name ::area/tamer-and-option)
      (update-in [::area/stacks]
                 (fn [stacks]
                   (filter tamer-and-option stacks)))
      (= area-name ::area/battle)
      (update-in [::area/stacks]
                 (fn [stacks]
                   (remove tamer-and-option stacks)))
      (and (= area-name ::area/hand)
           (->> available-actions
                (filter (fn [[action-key [_ player-id] _]]
                          (and (= action-key :action/re-draw?)
                               (= of-player player-id current-player-id))))
                seq))
      (assoc ::area/actions
             (->> available-actions
                  (filter (fn [[action-key [_ player-id] _]]
                            (and (= action-key :action/re-draw?)
                                 (= of-player player-id current-player-id))))
                  set))
      (and (= area-name ::area/digi-eggs)
           (->> available-actions
                (filter (fn [[action-key [_ player-id] _]]
                          (and (= action-key :action/hatch)
                               (= of-player player-id current-player-id))))
                seq))
      (assoc ::area/actions
             (->> available-actions
                  (filter (fn [[action-key [_ player-id] _]]
                            (and (= action-key :action/hatch)
                                 (= of-player player-id current-player-id))))
                  set)))))

(pc/defresolver action-params
  [{{:keys [game-id]} :path-params
    :as request} {action :action/action}]
  {::pc/input #{:action/action}
   ::pc/output [:action/params]}
  (let [{::game/keys [players cards-lookup]
         :as game} (get-in @state/state [::state/games-by-id
                                         (UUID/fromString game-id)])
        stacks-by-uuid (->> players
                            (mapcat (fn [{::player/keys [areas]}]
                                      (->> (vals areas)
                                           (filter (fn [{::area/keys [stacks]}]
                                                     (seq stacks)))
                                           (mapcat ::area/stacks))))
                            (reduce (fn [accl {::stack/keys [uuid] :as stack}]
                                      (assoc accl uuid
                                             (update stack
                                                     ::stack/cards
                                                     (fn [cards]
                                                       (mapv (fn [{card-uuid ::card/uuid
                                                                  :as card}]
                                                               (assoc card
                                                                      ::card/card
                                                                      (get-in cards-lookup
                                                                              [card-uuid "en"])))
                                                             cards)))))
                                    {}))
        [_ _ params] action]
    {:action/params
     (if-let [[param-type uuid] (and (s/valid? ::simulator/action-ident
                                               params)
                                     params)]
       (case param-type
         ::card/uuid {::card/uuid uuid
                      ::card/card (get-in cards-lookup
                                          [uuid "en"])}
         ::stack/uuid {::stack/uuid uuid}
         ::player/id (get (helpers/players-by-id players) uuid))
       (map (fn [param-fragment]
              (if-let [[param-type uuid] (and (s/valid? ::simulator/action-ident
                                                        param-fragment)
                                              param-fragment)]
                (case param-type
                  ::card/uuid {::card/uuid uuid
                               ::card/card (get-in cards-lookup
                                                   [uuid "en"])}
                  ::stack/uuid (get stacks-by-uuid uuid)
                  ::player/id (get (helpers/players-by-id players) uuid))
                param-fragment))
            params))}))

(pc/defresolver card-actions
  [_ {::game/keys [available-actions]
      ::card/keys [uuid]}]
  {::pc/input #{::game/available-actions
                ::card/uuid}
   ::pc/output [{::card/actions [:action/action]}]}
  (when-let [actions (->> available-actions
                          (filter (fn [[_ _ params]]
                                    (or (and (coll? params)
                                             (contains? (set params)
                                                        [::card/uuid uuid]))
                                        (= params [::card/uuid uuid]))))
                          seq)]
    {::card/actions (->> actions
                         (sort-by first)
                         (mapv (fn [[_ _ params :as action]]
                                 {:action/action action})))}))

(pc/defresolver stack-actions
  [_ {::game/keys [available-actions]
      ::stack/keys [uuid]}]
  {::pc/input #{::game/available-actions
                ::stack/uuid}
   ::pc/output [{::stack/actions [:action/action]}]}
  (when-let [actions (->> available-actions
                          (filter (fn [[_ _ params]]
                                    (or (and (coll? params)
                                             (= (first params)
                                                [::stack/uuid uuid]))
                                        (= params [::stack/uuid uuid]))))
                          seq)]
    {::stack/actions (->> actions
                          (sort-by first)
                          (mapv (fn [[_ _ params :as action]]
                                  {:action/action action})))}))

(pc/defresolver player-actions
  [_ {::game/keys [available-actions]
      ::player/keys [id]}]
  {::pc/input #{::game/available-actions
                ::player/id}
   ::pc/output [{::player/actions [:action/action]}]}
  (when-let [actions (->> available-actions
                          (filter (fn [[action-key [_ player-id] _]]
                                    (and (= player-id id)
                                         (contains? #{:phase/main
                                                      :action/pass
                                                      :action/attack.counter}
                                                    action-key))))
                          seq)]
    {::player/actions (->> actions
                           (sort-by first)
                           set)}))

(pc/defresolver game
  [{{{current-player-id ::player/id} :player} :session
    {:keys [game-id]} :path-params
    :as request} params]
  {::pc/output [::game/id
                {::game/in [::game-in/turn-index
                            ::game-in/state-id]}
                {::game/players [::player/id
                                 ::player/name
                                 ::player/deck-code
                                 ::player/language
                                 ::player/memory]}
                ::game/log
                ::game/available-actions
                ::game/turn-counter
                ::game/constraint-code]}
  (let [{::game/keys [available-actions log players cards-lookup]
         :as game} (get-in @state/state [::state/games-by-id
                                         (UUID/fromString game-id)])
        ended? (-> log last first
                   (= :game/end))]
    (-> game
        (dissoc ::game/random
                ::game/instant
                ::game/cards-lookup)
        (update ::game/available-actions
                (fn [available-actions]
                  (reduce (fn [accl [_ [_ id] _ :as action]]
                            (cond-> accl
                              (= id current-player-id)
                              (conj action)))
                          #{}
                          available-actions)))
        (update ::game/players
                (fn [players]
                  (mapv (fn [{::player/keys [id] :as player}]
                          (update (cond-> player
                                    (not ended?)
                                    (dissoc ::player/deck-code))
                                  ::player/areas
                                  (fn [{battle ::area/battle :as areas}]
                                    (-> areas
                                        (update-vals (fn [area]
                                                       (dissoc area
                                                               ::area/cards
                                                               ::area/stacks)))
                                        (assoc ::area/tamer-and-option
                                               (-> battle
                                                   (assoc ::area/name
                                                          ::area/tamer-and-option)
                                                   (dissoc ::area/cards
                                                           ::area/stacks)))))))
                        players))))))

(def resolver-registry
  [current-user
   area
   card-actions
   stack-actions
   player-actions
   action-params
   game])

(def parser
  (p/parser
   {::p/env {::p/reader [p/map-reader
                         pc/reader2
                         pc/open-ident-reader]
             ::p/placeholder-prefixes #{">"}}
    ::p/mutate pc/mutate
    ::p/plugins [(pc/connect-plugin {::pc/register resolver-registry})
                 p/error-handler-plugin
                 p/trace-plugin
                 (p/post-process-parser-plugin p/elide-not-found)]}))
