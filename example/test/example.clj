(ns example
  (:require [clojure.java.shell :as shell]
            [ig.havoc.core :as havoc]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [clojure.test :as t]
            util
            [clojure.tools.logging :as log]))

(def project-name "example-project")

(defn start-system []
  (log/info "Starting system")
  (shell/sh "docker-compose"
            "-p" project-name
            "-f" "docker-compose.yml" "up"
            "-d"
            "--build"
            "--force-recreate"))

(defn stop-system []
  (log/info "Stoping system")
  (shell/sh "docker-compose"
            "-p" project-name
            "-f" "docker-compose.yml" "down" "-v" "--remove-orphans"))

(def from [:our-service1 :our-service2])
(def to [:kafka1 :kafka2 :kafka3])
(def ok {:status :ok})

(def initial-state (into {:kafka1       ok
                          :kafka2       ok
                          :kafka3       ok
                          :our-service1 ok
                          :our-service2 ok}
                         (for [f from
                               t (cons :fake to)]
                           [[f t] ok])))

(def final-states #{initial-state})

(def command-generators
  (concat
    [(havoc/evil-http-server-gen [:fake 3001])]
    (map havoc/container-gen (concat from to))
    (for [f from
          t (cons :fake to)]
      (havoc/link-gen [f t]))
    (for [f from
          t (cons :fake to)]
      (havoc/link-handicaps-gen [f t]))))

(def at-least-once-property
  (prop/for-all [plan (havoc/random-plan-generator [2 10]
                                                   initial-state
                                                   final-states
                                                   command-generators)]
    (log/info (start-system))
    (try
      (let [docker (havoc/create-docker "http://localhost:2376" project-name)]
        (log/info "The plan is" (havoc/initial->broken plan))
        (util/start-sending! 10000)
        (doseq [cmd (havoc/initial->broken plan)]
          (log/info "Running" cmd)
          (havoc/exec! docker cmd)
          (Thread/sleep 10000))
        (doseq [cmd (havoc/broken->final plan)]
          (log/info "Back to good shape" cmd)
          (havoc/exec! docker cmd))
        (log/info "Waiting for messages to be sent")
        (util/wait-until-sent)
        (let [result
              (util/try-for (* 5 60)
                            (log/info "So far" (count (util/unique-messages)))
                            (= 10000 (count (util/unique-messages))))]
          (if-not result
            (log/warn "Broke!!!" (havoc/initial->broken plan)))
          result))
      (finally
        (log/info (stop-system))))))

(defn clean [plan]
  {:initial->broken (havoc/initial->broken plan)
   :broken->final   (havoc/broken->final plan)})

(t/deftest at-least-once
  (let [result (tc/quick-check 100 at-least-once-property
                 :seed 1478105260193)]
    (when-not (true? (:result result))
      (t/is false
            (pr-str (-> result
                        (update-in [:fail 0] clean)
                        ;(dissoc :fail)
                        (update-in [:shrunk :smallest 0] clean)
                        ))))))

(comment
  (start-system)
  (util/start-sending! 10000)
  (stop-system)

  (count (util/unique-messages))

  (at-least-once)

  (def docker (havoc/create-docker "http://localhost:2376" project-name))

  (havoc/exec! docker
               {:command :container/start
                :host    :zoo1})

  (havoc/exec! docker
               {:command :link/fix
                :from    :zoo1
                :to      :kafka1})

  (havoc/exec! docker {:command :link/flaky
                       :from    :zoo1
                       :to      :kafka1
                       :delay   {:time        100
                                 :jitter      1000
                                 :correlation 90}
                       :loss    {:percent     30
                                 :correlation 75}
                       :corrupt {:percent 5}})

  (havoc/exec! docker
               {:command   :http-server/set!
                :host-port [:fake 3001]
                :state     {:faults         #{:random-header
                                              :random-content-length
                                              :random-http-status}
                            :error-code     429
                            :content-length -12323}
                })

  )