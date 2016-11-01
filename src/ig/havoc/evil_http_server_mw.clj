(ns ig.havoc.evil-http-server-mw
  (:require [clojure.edn :as edn]
            byte-streams)
  (:import (java.util Random)))

(def rnd (Random.))

(defn random-array [size]
  (let [array (byte-array size)]
    (.nextBytes rnd array)
    array))

(defn random-response [{num-bytes :response-length :or {num-bytes 32}} resp]
  (let [rem (rem num-bytes 64)
        times (cond-> (/ num-bytes 64)
                      (not (zero? rem)) dec)]
    (assoc resp :body (byte-streams/to-input-stream
                        (concat
                          (repeatedly times #(random-array 64))
                          [(random-array rem)])))))

(defn infite-response [_ resp]
  (random-response {:response-length (Long/MAX_VALUE)} resp))

(defn empty-response [_ resp]
  (dissoc resp :body))

(defn random-header [_ resp]
  (assoc-in resp
            [:headers (byte-streams/to-string (random-array 24))]
            (byte-streams/to-string (random-array 48))))

(defn incorrect-content-length [{:keys [content-length]} resp]
  (assoc-in resp [:headers "content-length"] (str content-length)))

(defn random-status [{:keys [error-code]} resp]
  (assoc resp :status error-code))

(def evil-fns {:infinite-response     infite-response
               :random-response       random-response
               :empty-response        empty-response
               :random-header         random-header
               :random-content-length incorrect-content-length
               :random-http-status    random-status})

(defn apply-evilness [config handler req]
  (let [resp (handler (assoc req :evil-server/config config))]
    (reduce (fn [resp-so-far handicap]
              ((get evil-fns handicap) config resp-so-far))
            resp
            (get config :faults))))

(defn parse-body [req]
  (edn/read-string (slurp (:body req))))

(defn create-ring-mw [handler]
  (let [config (atom {})]
    (fn [req]
      (if (= (:uri req) "/ig-havoc")
        (do
          (reset! config (parse-body req))
          {:status 200})
        (apply-evilness @config handler req)))))
