(ns ig.havoc.core
  (:require potemkin
            ig.havoc.impl.command-generator
            ig.havoc.impl.docker
            [ig.havoc.impl.docker-command-generator :as docker-gen]
            ig.havoc.impl.evil-http-server))

(potemkin/import-vars

  [ig.havoc.impl.docker

   create-docker]

  [ig.havoc.impl.docker-command-generator

   link-handicaps
   link-states
   container-states]

  [ig.havoc.impl.evil-http-server

   evil-http-server-faults
   evil-http-server-gen]

  [ig.havoc.impl.command-generator

   random-plan-generator
   exec!
   final->initial
   broken->final
   initial->broken
   commands-from-to])

(defn container-gen [host & {:keys [states]}]
  (docker-gen/states host (or states container-states)))

(defn link-gen [[from-host to-host] & {:keys [states]}]
  (docker-gen/states [from-host to-host] (or states link-states)))

(defn link-handicaps-gen [[from-host to-host] & {:keys [handicaps]}]
  (docker-gen/handicap [from-host to-host] (or handicaps link-handicaps)))

(defn final-state [plan]
  (:ig.havoc.impl.command-generator/final-state plan))

(defn at-least-one-container-ok [containers]
  (let [containers (set containers)
        ok? (fn [[_ {:keys [status]}]]
              (= :ok status))]
    (fn [{:keys [action item]} old-state _]
      (let [all-still-ok (map key
                              (filter ok?
                                      (select-keys old-state containers)))]
        (not (and (= item (first all-still-ok))
                  (= 1 (count all-still-ok))
                  (#{:container/kill
                     :container/stop
                     :container/restart
                     :container/pause} action)))))))