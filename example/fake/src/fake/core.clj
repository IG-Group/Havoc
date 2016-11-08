(ns fake.core
  (:require
    [compojure.core :refer [defroutes ANY GET]]
    [ring.adapter.jetty :as jetty]
    [ig.havoc.evil-http-server-mw :as evil]
    [clojure.tools.nrepl.server :refer (start-server)]
    [franzy.serialization.serializers :as serializers]
    [franzy.clients.producer.client :as client]
    [franzy.clients.producer.protocols :as producer]
    [clojure.tools.logging :as log])
  (:use ring.middleware.params)
  (:gen-class))

(defn for-ever
  [thunk]
  (loop []
    (if-let [result (try
                      [(thunk)]
                      (catch Exception e
                        (println e)
                        (Thread/sleep 100)))]
      (result 0)
      (recur))))

(def kafka-client (delay
                    (client/make-producer {:bootstrap.servers "kafka1:9092,kafka2:9092,kafka3:9092"
                                           :acks              "all"
                                           :retries           1
                                           :client.id         "example-producer"}
                                          (serializers/keyword-serializer)
                                          (serializers/edn-serializer))))

(defn produce-edn [content]
  (for-ever
    #(producer/send-sync! @kafka-client {:topic "THE.TEST"
                                         :value content})))

(def received (atom #{}))
(def sending (atom false))

(defn produce [from to]
  (log/debug "producing" from)
  (when-not (zero? (- to from))
    (produce-edn {:id from
                  :ts (System/currentTimeMillis)})
    (recur (inc from) to)))

(comment
  (produce 10 12))

(defroutes api
  (ANY "/" {:keys [body]}
    (let [msg (read-string (slurp body))]
      (swap! received conj (:id msg)))
    {:status 200
     :body   "received!"})
  (GET "/msgs-ids" []
    {:status 200
     :body   (pr-str {:done (not @sending)
                      :ids  @received})})
  (GET "/send-msgs" [from to]
    (assert (and from to) "from or to missing")
    (reset! received #{})
    (reset! sending true)
    (future
      (produce (Integer/parseInt from)
               (Integer/parseInt to))
      (reset! sending false))
    {:status 204}))

(defn -main [& args]
  (start-server :port 3002 :bind "0.0.0.0")
  (jetty/run-jetty
    (evil/create-ring-mw
      (wrap-params (var api)))
    {:port  80
     :join? true}))
