(ns util
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]))

(defmacro try-for [how-long & body]
  `(let [start-time# (System/currentTimeMillis)]
     (loop []
       (let [result# (do
                       ~@body)]
         (or result#
             (if (> (* ~how-long 1000)
                    (- (System/currentTimeMillis) start-time#))
               (do
                 (Thread/sleep 1000)
                 (recur))
               result#))))))

(defn http-get [path & [req]]
  (try
    (let [response (http/get (str "http://localhost:3001" path)
                             (merge req
                                    {:socket-timeout     5000
                                     :connection-timeout 1000}))]
      (log/debug path response)
      response)
    (catch Exception e (log/debug path e))))

(defn start-sending! [how-many]
  (try-for (* 5 60)
           (try
             (http-get "/send-msgs"
                       {:query-params {:from 0
                                       :to   how-many}})
             (catch Exception _))))

(defn wait-until-sent []
  (try-for (* 5 60)
           (-> (http-get "/msgs-ids")
               :body
               read-string
               :done)))

(defn unique-messages []
  (-> (http-get "/msgs-ids")
      :body
      read-string
      :ids))