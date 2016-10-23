(ns ig.havoc.impl.docker-command-generator
  (:require clojure.data
            clojure.set
            [clojure.test.check.generators :as gen]
            [clojure.test.check.rose-tree :as rose]
            [clojure.test.check.random :as random]
            [ig.havoc.impl.command-generator :as core]
            loom.graph
            loom.attr
            loom.alg))

(defn key-with-val [m v]
  (get (clojure.set/map-invert m) v))

(defn handicap [name handicaps]
  (letfn [(this-state [state]
            (set (get-in state [name :handicaps])))
          (possible-actions [state]
            (let [current-state (this-state state)]
              (concat
                (remove current-state (keys handicaps))
                (vals (select-keys handicaps current-state)))))]
    (reify core/Command
      (precondition [_ state] true)
      (postcondition [_ state {action :action}]
        (let [possible-action (possible-actions state)]
          (possible-action action)))
      (exec [_ state {action :action}]
        (update-in state [name :handicaps]
                   (fn [current-state]
                     (if (contains? handicaps action)
                       (conj (or current-state #{}) action)
                       (disj current-state (key-with-val handicaps action))))))
      (generate [_ state]
        (gen/elements
          (for [a (possible-actions state)]
            {:item   name
             :action a})))
      (move-to [_ from-state target-state]
        (let [[to-add to-remove] (clojure.data/diff (this-state target-state) (this-state from-state))]
          (map (fn [a]
                 {:item   name
                  :action a})
               (concat to-add
                       (map handicaps to-remove))))))))

(defn states [name state-machine]
  (let [this-state (fn [state]
                     (get-in state [name :status]))
        possible-actions (fn [state]
                           (let [current-state (this-state state)]
                             (set (keys (get state-machine current-state)))))
        loom-graph (reduce (fn [g [start-state actions]]
                             (reduce (fn [g [action final-state]]
                                       (-> g
                                           (loom.graph/add-edges [start-state final-state])
                                           (loom.attr/add-attr [start-state final-state] :action action)))
                                     g
                                     actions))
                           (loom.graph/digraph)
                           state-machine)]
    (reify core/Command
      (precondition [_ state] true)
      (postcondition [_ state {action :action}]
        (let [possible-action (possible-actions state)]
          (possible-action action)))
      (exec [_ state {action :action}]
        (update-in state [name :status]
                   (fn [current-state]
                     (get-in state-machine [current-state action]))))
      (generate [_ state]
        (gen/elements
          (for [a (possible-actions state)]
            {:item   name
             :action a})))
      (move-to [_ from-state target-state]
        (map (fn [edge]
               {:item   name
                :action (loom.attr/attr loom-graph (vec edge) :action)})
             (partition 2 1
                        (loom.alg/bf-path loom-graph
                                          (this-state from-state)
                                          (this-state target-state))))))))

(def container-states
  {:ok      {:container/stop    :stopped
             :container/kill    :stopped
             ;:container/pause   :paused                     ;; TODO: on start/unpause, execute all the handicaps + broken links. or it should just pause the java process???
             :container/restart :ok}
   :stopped {:container/start :ok}
   ;:paused  {:container/unpause :ok}
   })

(def link-states
  {:ok     {:link/cut :cutted}
   :cutted {:link/fix :ok}})

(def link-handicaps {:link/delay     :unlag
                     :link/big-delay :remove-delay
                     :link/corrupt   :uncorrupt
                     :link/loss      :not-loss
                     ;:slow-write :fast-write             ;; TODO:!!!!
                     ;:slow-read  :fast-read
                     })

(defmacro def->docker-status-command [cmd]
  `(defmethod core/->docker ~cmd [cmd# _# _#]
     (clojure.set/rename-keys cmd# {:item :host :action :command})))

(doseq [container-actions (set (mapcat (comp keys val) container-states))]
  (def->docker-status-command container-actions))

(defmethod core/->docker :link/cut [{:keys [item]} _ _]
  {:command :link/cut
   :from    (first item)
   :to      (second item)})

(defmethod core/->docker :link/fix [{:keys [item]} _ _]
  {:command :link/fix
   :from    (first item)
   :to      (second item)})

(defmacro def->docker-link-handicap [x]
  `(defmethod core/->docker ~x [cmd# _# new-state#]
     (let [obj# (:item cmd#)
           handicaps# (get-in new-state# [obj# :handicaps] #{})]
       (cond-> {:command :link/fast
                :from    (first obj#)
                :to      (second obj#)}

               (seq handicaps#) (assoc :command :link/flaky)
               (handicaps# :link/loss) (assoc :loss {:percent     7
                                                     :correlation 25})
               (handicaps# :link/corrupt) (assoc :corrupt {:percent 5})
               (handicaps# :link/delay) (assoc :delay {:time        1000
                                                       :jitter      500
                                                       :correlation 75})
               (handicaps# :link/big-delay) (assoc :delay {:time        120000
                                                           :jitter      60000
                                                           :correlation 75})))))

(doseq [handicap (mapcat identity link-handicaps)]
  (def->docker-link-handicap handicap))