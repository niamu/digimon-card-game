(ns dcg.simulator.server.core
  (:gen-class)
  (:require
   [clojure.edn :as edn]
   [dcg.db :as db]
   [dcg.simulator :as-alias simulator]
   [dcg.simulator.player :as-alias player]
   [dcg.simulator.state :as state]
   [dcg.simulator.server.render :as render]
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.defaults :as defaults]
   [ring.middleware.session.cookie :as cookie]
   [ring.util.response :as response])
  (:import
   [java.util UUID]))

(def routes
  [["/"
    {:name ::index
     :get (fn [{{:keys [player]} :session :as request}]
            (state/initialize-from-queue!)
            (let [player (or player {::player/id (random-uuid)})]
              (if-let [game-id (state/player-in-game? player)]
                (response/redirect (str "/game/" game-id))
                (cond-> {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body (render/index player)}
                  (nil? (get-in request [:session :player]))
                  (assoc-in [:session :player] player)))))}]
   ["/queue"
    {:name ::queue
     :post (fn [{{:keys [player]} :session
                :keys [params]
                :as request}]
             (let [player (merge player
                                 {::player/name (:username params)
                                  ::player/deck-code (:deck-code params)})]
               (swap! state/state update ::state/queue conj player)
               (response/redirect "/")))}]
   ["/game/:game-id"
    {:name ::game
     :get {:parameters {:path {:game-id uuid?}}
           :handler (fn [{{:keys [player]} :session
                         {:keys [game-id]} :path-params
                         :as request}]
                      (if-let [game (some-> @state/state
                                            (get-in [::state/games-by-id
                                                     (UUID/fromString game-id)])
                                            (state/private-state-for-player-id
                                             (::player/id player)))]
                        {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body (render/game game player)}
                        {:status 404
                         :headers {"Content-Type" "text/html"}
                         :body "Not Found"}))}
     :post {:parameters {:path {:game-id uuid?}}
            :handler (fn [{{:keys [player]} :session
                          {:keys [game-id]} :path-params
                          {:keys [action params]} :params
                          :as request}]
                       (let [action (edn/read-string action)
                             params (edn/read-string params)]
                         (swap! state/state update-in
                                [::state/games-by-id (UUID/fromString game-id)]
                                (fn [game]
                                  (state/flow game
                                              [action
                                               (::player/id player)
                                               params])))
                         (response/redirect (str "/game/" game-id))))}}]])

(defonce ^:private store-key
  (.getBytes "a6505635aa8b426c"
             #_(subs (clojure.string/replace (random-uuid) "-" "") 0 16)))

(def route-handler
  (ring/ring-handler
   (ring/router routes)
   (ring/create-default-handler
    {:not-found          (constantly {:status 404
                                      :headers {"Content-Type" "text/html"}
                                      :body "Not Found"})
     :method-not-allowed (constantly {:status 404
                                      :headers {"Content-Type" "text/html"}
                                      :body "Method Not Allowed"})
     :not-acceptable     (constantly {:status 406
                                      :headers {"Content-Type" "text/html"}
                                      :body "Not Acceptable"})})))

(def handler
  (-> #'route-handler
      (defaults/wrap-defaults
       (-> defaults/secure-site-defaults
           (assoc-in [:security :ssl-redirect] false)
           (update-in [:session :cookie-attrs] merge
                      {:same-site :lax
                       :secure false
                       ;; 1 Year expiration
                       :max-age (* 60 60 24 365)})
           (update-in [:session] merge
                      {:store (cookie/cookie-store {:key store-key})
                       :cookie-name "player"})))))

(defn -main
  [& args]
  (db/import-from-file!)
  (jetty/run-jetty #'handler
                   {:join? false
                    :ssl? false
                    :port 3000}))
