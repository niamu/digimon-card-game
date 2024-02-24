(ns dcg.simulator.server.core
  (:gen-class)
  (:require
   [dcg.simulator.fsm :as fsm]
   [dcg.simulator.server.render :as render]
   [reitit.core :as r]
   [reitit.ring :as ring]
   [reitit.coercion :as coercion]
   [reitit.coercion.spec :as spec]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.exception :as reitit-exception]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults :as defaults]
   [ring.middleware.multipart-params :as multipart-params]
   [ring.middleware.not-modified :as not-modified]
   [ring.middleware.resource :as resource]))

(def error404
  {:status 404
   :headers {"Content-Type" "text/html"}
   :body "Not Found"})

(defonce game-state
  (-> (fsm/step nil
                :game/start
                [{:player/name "niamu"
                  :player/deck-codec "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
                 {:player/name "AI"
                  :player/deck-codec "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}])
      (fsm/step :phase/mulligan?decline nil)
      (fsm/step :phase/mulligan?accept nil)))

(defn response
  [request]
  (if-let [route-name (get-in request [::r/match :data :name])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (-> game-state
               (fsm/step :phase/raising?hatch nil)
               (fsm/step :phase/main.digivolve ["card/en_ST1-03_P0" :zone/raising-area 1])
               (fsm/step :phase/main.digivolve ["card/en_ST1-06_P0" :zone/raising-area 1])
               (fsm/step :phase/raising?hatch nil)
               (fsm/step :phase/main.digivolve ["card/en_ST2-04_P0" :zone/raising-area 1])
               (fsm/step :phase/main.digivolve ["card/en_ST2-06_P0" :zone/raising-area 1])
               (fsm/step :phase/main.digivolve ["card/en_ST2-09_P0" :zone/raising-area 1])
               (fsm/step :phase/raising?do-nothing nil)
               (fsm/step :phase/main.digivolve ["card/en_ST1-09_P0" :zone/raising-area 1])
               (fsm/step :phase/main.play-tamer ["card/en_ST1-12_P0"])
               (fsm/step :phase/raising?do-nothing nil)
               (fsm/step :phase/main.digivolve ["card/en_ST2-10_P0" :zone/raising-area 1])
               (fsm/step :phase/end nil)
               (fsm/step :phase/raising?move-to-battle-area nil)
               #_(fsm/step :phase/main.attack [2 nil])
               #_(fsm/step :phase/main.digivolve ["card/en_ST1-11_P0" :zone/battle-area 2])
               (render/game-board #uuid "c7005f93-e754-3dce-88bb-3ccc64dee208"))
     #_(-> (fsm/step nil
                     :game/start
                     [{:player/name "niamu"
                       :player/deck-codec "DCGAREdU1QxIEHBU1QxIE_CwcHBwUHBwUFBwcHBQUFTdGFydGVyIERlY2ssIEdhaWEgUmVkIFtTVC0xXQ"}
                      {:player/name "AI"
                       :player/deck-codec "DCGARMhU1QyIEHBU1QyIE_CwcHBQcHBwUFBwcFBwUFTdGFydGVyIERlY2ssIENvY3l0dXMgQmx1ZSBbU1QtMl0"}])
           (fsm/step :phase/mulligan?accept nil)
           (fsm/step :phase/mulligan?decline nil)
           (fsm/step :phase/raising?hatch nil)
           (fsm/step :phase/main.play-digimon ["card/en_ST2-03_P0"])
           (fsm/step :phase/raising?hatch nil)
           (fsm/step :phase/main.play-digimon ["card/en_ST1-03_P0"])
           (fsm/step :phase/main.digivolve ["card/en_ST1-07_P0" :zone/battle-area 1])
           (fsm/step :phase/main.play-tamer ["card/en_ST2-12_P0"])
           (fsm/step :phase/main.play-option ["card/en_ST2-13_P0"])
           (fsm/step :phase/main.digivolve ["card/en_ST2-07_P0" :zone/battle-area 1])
           (fsm/step :phase/main.digivolve ["card/en_ST1-08_P0" :zone/battle-area 1])
           (fsm/step :phase/main.attack [1 nil])
           (fsm/step :phase/main.play-digimon ["card/en_ST2-07_P0"])
           (fsm/step :phase/main.attack [1 1])
           (fsm/step :phase/main.attack.block?accept 3)
           (fsm/step :phase/main.digivolve ["card/en_ST1-10_P0" :zone/battle-area 1])
           (fsm/step :phase/main.play-tamer ["card/en_ST1-12_P0"])
           (fsm/step :phase/main.play-digimon ["card/en_ST1-05_P0"])
           (fsm/step :phase/main.attack [1 nil])
           (fsm/step :phase/main.play-digimon ["card/en_ST2-05_P0"])
           (fsm/step :phase/main.play-digimon ["card/en_ST2-06_P0"])
           (fsm/step :phase/main.attack [1 nil])
           (fsm/step :phase/main.play-digimon ["card/en_ST1-06_P0"])
           (fsm/step :phase/main.attack [3 nil])
           (fsm/step :phase/end nil)
           (fsm/step :phase/main.digivolve ["card/en_ST2-08_P0" :zone/battle-area 3])
           (fsm/step :phase/main.attack [2 nil])
           (fsm/step :phase/main.attack.block?accept 3)
           (fsm/step :phase/main.attack [2 nil])
           (fsm/step :phase/main.play-digimon ["card/en_ST2-10_P0"])
           (fsm/step :phase/main.attack [1 nil])
           (fsm/step :phase/main.attack [3 nil])
           (render/game-board #uuid "c7005f93-e754-3dce-88bb-3ccc64dee208"))}
    error404))

(defn routes
  [handler opts]
  (ring/router
   ["" {:handler handler}
    ["/" ::index]]
   opts))

(def route-handler
  (ring/ring-handler
   (routes response
           {:data
            {:middleware [(reitit-exception/create-exception-middleware
                           (merge
                            reitit-exception/default-handlers
                            {::coercion/request-coercion
                             (constantly error404)}))
                          rrc/coerce-request-middleware
                          rrc/coerce-response-middleware]
             :coercion spec/coercion}})
   response))

(def handler
  (-> #'route-handler
      multipart-params/wrap-multipart-params
      (resource/wrap-resource "")
      (defaults/wrap-defaults
       (-> defaults/site-defaults
           (assoc-in [:security :anti-forgery] false)))
      not-modified/wrap-not-modified))

(defn -main
  [& args]
  (jetty/run-jetty #'handler
                   {:join? false
                    :ssl? false
                    :port 3000}))
