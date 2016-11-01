(ns fake.core
  (:require
    [compojure.core :refer [defroutes ANY]]
    [ring.adapter.jetty :as jetty]
    [ig.havoc.evil-http-server-mw :as evil])
  (:use ring.middleware.params)
  (:gen-class))

(defroutes api
           (ANY "/" []
                {:status 200
                 :body   "hi there!"}))

(defn -main [& args]
  (jetty/run-jetty
    (evil/create-ring-mw
      (wrap-params (var api)))
    {:port  80
     :join? true}))