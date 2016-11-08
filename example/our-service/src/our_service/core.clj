(ns our-service.core
  (:require
    [clojure.tools.nrepl.server :refer (start-server)]
    [franzy.clients.consumer.client :as client]
    [franzy.clients.consumer.protocols :as consumer]
    [franzy.clients.consumer.defaults :as cd]
    [franzy.serialization.deserializers :as deserializers]
    [clj-http.client :as http]
    [clojure.tools.logging :as log]
    clj-http.conn-mgr)
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

(defn subscribing-consumer []
  (let [c (client/make-consumer {:bootstrap.servers       ["kafka1:9092" "kafka2:9092" "kafka3:9092"]
                                 :group.id                "example-consumer"
                                 :auto.offset.reset       :earliest
                                 :enable.auto.commit      true
                                 :metadata.max.age.ms     30000
                                 :auto.commit.interval.ms 1000}
                                (deserializers/byte-array-deserializer)
                                (deserializers/edn-deserializer)
                                (cd/make-default-consumer-options))]
    (consumer/subscribe-to-partitions! c ["THE.TEST"])
    c))

(defn -main
  [& x]
  (future
    (try
      (log/info "Starting consumer")
      (let [kafka-consumer (for-ever #(subscribing-consumer))
            connection-pool (clj-http.conn-mgr/make-reusable-conn-manager {:timeout 10 :threads 1})]
        (while true
          (doseq [msg (consumer/poll! kafka-consumer)]
            (log/info "processing" msg)
            (http/post "http://fake/" {:connection-manager connection-pool
                                       :body               (pr-str (:value msg))}))))
      (catch Throwable t
        (log/info t "Something went wrong"))
      (finally
        (log/info "Exiting consumer :("))))
  (start-server :port 3002 :bind "0.0.0.0"))
