(ns dcg.simulator.effect-test
  (:require
   [clojure.test :as t]
   [dcg.db.db :as db]
   [dcg.simulator.effect :as effect]
   [dcg.simulator.card :as-alias card]
   [dcg.simulator.game :as-alias game]
   [dcg.simulator.player :as-alias player]))

(defn- load-db
  [f]
  (db/import-from-file!)
  (f))

(t/use-fixtures :once load-db)

(defn- load-card
  [number]
  (db/q '{:find [(pull ?c [:card/effect
                           :card/inherited-effect
                           :card/security-effect]) .]
          :in [$ ?n ?l]
          :where [[?c :card/number ?n]
                  [?c :card/language ?l]
                  [?c :card/image ?i]
                  [?i :image/language ?l]]}
        number
        "en"))

(t/deftest st2-13
  (t/testing "ST2-13 [Main] effect"
    (t/is (= (->> (effect/transform {::game/cards-lookup
                                     {1 {"en" (load-card "ST2-13")}}
                                     ::game/players [{::player/id 1
                                                      ::player/name "Player 1"
                                                      ::player/memory 2}
                                                     {::player/id 2
                                                      ::player/name "Player 2"
                                                      ::player/memory -2}]}
                                    [:action/effect
                                     [::player/id 1]
                                     [[::card/uuid 1] :card/effect 0]])
                  ::game/players
                  (map ::player/memory))
             '(3 -3)))))
