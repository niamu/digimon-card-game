(ns dcg.api.router
  (:require
   [dcg.api.resources.errors :as errors]
   [dcg.api.routes :refer [routes]]
   [reitit.core :as r]
   [reitit.ring :as ring]
   [reitit.coercion.spec :as spec]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.exception :as exception]))

(declare by-name)

(defn- resolve-handlers
  "Recursively walk a route tree, turning handler symbols into fns."
  [route]
  (cond
    ;; Route with path & opts only
    (and (vector? route)
         (map? (second route)))
    (let [[path opts] route]
      [path (update opts :handler #(if (symbol? %) (requiring-resolve %) %))])

    ;; Route with path, opts, and children
    (and (vector? route)
         (map? (second route))
         (sequential? (drop 2 route)))
    (let [[path opts & children] route]
      (into [path (update opts :handler #(if (symbol? %) (requiring-resolve %) %))]
            (map resolve-handlers children)))

    ;; Route with path and children, but no opts
    (and (vector? route)
         (sequential? (second route)))
    (let [[path & children] route]
      (into [path] (map resolve-handlers children)))

    :else route))

(defn router
  []
  (ring/router (map resolve-handlers routes)
               {:data
                {:coercion spec/coercion
                 :middleware [(exception/create-exception-middleware
                               {::exception/default (fn [exception request]
                                                      (errors/error406))})
                              coercion/coerce-request-middleware
                              coercion/coerce-response-middleware]}}))

(defn by-name
  [name & [{:keys [path query]}]]
  (-> (router)
      (r/match-by-name name path)
      (r/match->path query)))

(defn route-handler
  []
  (ring/ring-handler
   (router)
   (ring/routes
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler {:not-found #'errors/error404
                                  :method-not-allowed #'errors/error405
                                  :not-acceptable #'errors/error406}))))
