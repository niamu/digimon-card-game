(ns dcg.simulator.server.render
  (:require
   [hiccup.page :as page]))

(defn game-board
  [{:game/keys [current-turn moves players]} active-player]
  (page/html5
   {:mode :html}
   [:head
    [:title "Digimon Card Game"]
    (page/include-css "/css/hello.css")]
   [:body
    [:span (pr-str (keys (sort-by (fn [[uuid player]]
                                    (= (:player/name active-player)
                                       (:player/name player)))
                                  players)))]
    #_[:ul#moves
       (for [move moves]
         [:li (pr-str move)])]]))
