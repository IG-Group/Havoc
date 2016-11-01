(ns example
  (:require [clojure.java.shell :as shell]
            [ig.havoc.core :as havoc]))

(def project-name "example-project")

(defn start-system []
  (shell/sh "docker-compose"
            "-p" project-name
            "-f" "docker-compose.yml" "up"
            "-d"
            "--build"
            "--force-recreate"))

(defn stop-system []
  (shell/sh "docker-compose"
            "-p" project-name
            "-f" "docker-compose.yml" "down" "-v" "--remove-orphans"))

(comment
  (start-system)

  (shell/sh "docker-compose"
            "-p" project-name
            "-f" "example/docker-compose.yml" "up"
            "-d"
            "--build"
            "--force-recreate")

  (shell/sh "docker-compose"
            "-p" project-name
            "-f" "example/docker-compose.yml" "down" "-v" "--remove-orphans")

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