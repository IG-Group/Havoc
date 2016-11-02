(ns ig.havoc.impl.docker
  (:require
    [clojure.string :as s]
    [ig.havoc.impl.command-generator :as core]
    [clj-http.client :as http-client]
    [cheshire.core :as json]
    byte-streams
    [slingshot.slingshot :refer [try+ throw+]]
    [selmer.parser :as selmer]
    [clojure.tools.logging :as log])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.io InputStream)))

(def resp-streams [:stdin :stdout :stderr])
(def ^int ^:const resp-buf-size 1024)

(defn read-record
  [^InputStream dis]
  (let [resp-buf (byte-array resp-buf-size)
        ^ByteBuffer hdr-bb (doto (ByteBuffer/wrap resp-buf)
                             (.order ByteOrder/BIG_ENDIAN))
        n (.read dis resp-buf 0 8)]
    (when (pos? n)
      (assert (= n 8))
      (.rewind hdr-bb)
      (.getInt hdr-bb)                                      ; skip 4 bytes
      (let [t (int (aget ^bytes resp-buf 0))
            n (.getInt hdr-bb)
            s (loop [r ""
                     n n]
                (if (pos? n)
                  (let [n2 (.read dis resp-buf 0 (min n resp-buf-size))
                        r (str r (String. ^bytes resp-buf 0 n2))]
                    (if (pos? n2)
                      (recur r (- n n2))
                      r))
                  r))]
        [(resp-streams t) s]))))

(defn create-docker [docker-url docker-compose-project-name]
  (letfn [(clean-project-name []
            (s/replace docker-compose-project-name #"-" ""))
          (container-info [idx full-container-info]
            [(-> full-container-info :Labels :com.docker.compose.service keyword)
             {:ip        (-> full-container-info :NetworkSettings :Networks first val :IPAddress)
              :name      (.substring (-> full-container-info :Names first) 1)
              :tc-number (* 10 (inc idx))}])
          (correct-project? [full-container-info]
            (= (clean-project-name)
               (-> full-container-info :Labels :com.docker.compose.project)))]
    {:docker-url docker-url
     :project    (clean-project-name)
     :services   (->> (http-client/get (str docker-url "/containers/json") {:as :json})
                      :body
                      (filter correct-project?)
                      (sort-by :name)
                      (map-indexed container-info)
                      (into {}))}))

(defn service->name [docker service]
  (-> docker :services service :name))

(defn service->ip [docker service]
  (-> docker :services service :ip))

(defn service->tc-number [docker service]
  (-> docker :services service :tc-number))

(defn exec [{:keys [docker-url] :as docker} service cmd]
  (let [request {:method       :post
                 :url          (str docker-url "/containers/" (service->name docker service) "/exec")
                 :as           :json
                 :content-type :json
                 :body         (json/generate-string {:AttachStdin false :AttachStdout true :AttachStderr true
                                                      :Tty         false :Cmd cmd})}]
    (if-let [exec (try+ (http-client/request request)
                        (catch [:status 500] {:keys [body]}
                          (if (.contains body "is not running")
                            (log/debug "Ignoring " cmd " as service is not running")
                            (throw+))
                          false))]
      (if-let [exec-id (-> exec :body :Id)]
        (->
          (http-client/request {:method       :post
                                :url          (str docker-url "/exec/" exec-id "/start")
                                :content-type :json
                                :as           :byte-array
                                :body         (json/generate-string {:Detach false :Tty false})})
          :body
          byte-streams/to-input-stream
          read-record
          (log/debug "running" cmd))
        (throw (ex-info "exec response has no id" {:request  request
                                                   :response exec
                                                   :docker   docker
                                                   :service  service}))))))

(defn live-cycle! [docker service live-cycle-command]
  (http-client/request {:method :post
                        :url    (str (:docker-url docker)
                                     "/containers/" (service->name docker service) "/" (name live-cycle-command))}))

(defmacro def-livecycle [x cmd]
  `(defmethod core/exec! ~cmd [docker# params#]
     (live-cycle! docker# (:host params#) ~x)))

(doseq [x [:start :stop :pause :unpause :kill :restart]]
  (def-livecycle x (keyword "container" (name x))))

(defmethod core/exec! :link/cut [docker {:keys [from to]}]
  (exec docker from ["su" "-c" (str "iptables -A INPUT -s " (service->ip docker to) " -j DROP")]))

(defmethod core/exec! :link/fix [docker {:keys [from to]}]
  (exec docker from ["su" "-c" (str "iptables -D INPUT -s " (service->ip docker to) " -j DROP")]))

(def allow-all-traffic
  "tc qdisc add dev eth0 root handle 1: htb default 99;
   tc class add dev eth0 parent 1: classid 1:99 htb rate 1000mbit;
   tc qdisc add dev eth0 parent 1:99 handle 99: pfifo limit 5000;")

(defmethod core/exec! :link/flaky [docker {:keys [from to delay loss corrupt]}]
  (let [delay-str (when delay
                    (selmer/render
                      "delay {{time}}ms {{jitter|default:1}}ms {{correlation|default:0}}% distribution normal"
                      delay))
        loss-str (when loss
                   (selmer/render
                     "loss {{percent}}% {{correlation|default:0}}%"
                     loss))
        corrupt-str (when corrupt
                      (selmer/render
                        "corrupt {{percent}}%"
                        corrupt))]
    (exec docker from
          ["su" "-c"
           (selmer/render
             (str allow-all-traffic
                  "tc class replace dev eth0 parent 1: classid 1:{{tc-number}} htb rate 1000mbit;
                   tc filter replace dev eth0 parent 1: protocol ip prio {{tc-number}} u32 flowid 1:{{tc-number}} match ip dst {{ip}};
                   tc qdisc replace dev eth0 parent 1:{{tc-number}} handle {{handle-number}}: netem {{loss}} {{delay}} {{corrupt}}")
             {:tc-number     (service->tc-number docker to)
              :ip            (service->ip docker to)
              :handle-number (service->tc-number docker to)
              :loss          loss-str
              :delay         delay-str
              :corrupt       corrupt-str})])))

(defmethod core/exec! :link/fast [docker {:keys [from to]}]
  (exec docker from
        ["su" "-c"
         (selmer/render
           "tc filter del dev eth0 prio {{tc-number}};
            tc qdisc del dev eth0 parent 1:{{tc-number}};"
           {:ip        (service->ip docker to)
            :tc-number (service->tc-number docker to)})]))