(ns ig.havoc.impl.command-generator
  "Based on http://blog.guillermowinkler.com/blog/2015/04/12/verifying-state-machine-behavior-using-test-dot-check/"
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.rose-tree :as rose]))

(defmulti ->docker (fn [cmd old-state new-state] (:action cmd)))
(defmulti exec! (fn [docker command] (:command command)))

(defprotocol Command
  (precondition [this state] "Returns true if command can be applied in current system state")
  (postcondition [this state cmd] "Returns true if cmd can be applied on specified state")
  (exec [this state cmd] "Applies command in the specified system state, returns new state")
  (generate [this state] "Generates command given the current system state, returns command")
  (move-to [this from-state target-state] "Returns a list of commands to reach the target state"))


(defn valid-sequence?
  [global-constraint state cmd-seq sub-seq-idxs]
  (when (seq sub-seq-idxs)
    (let [states (rest (reductions (fn [curr-state state-idx]
                                     (let [[generator command] (get cmd-seq state-idx)]
                                       (if (postcondition generator curr-state command)
                                         (let [new-state (exec generator curr-state command)]
                                           (if (global-constraint command curr-state new-state)
                                             new-state
                                             (reduced ::invalid)))
                                         (reduced ::invalid))))
                                   state
                                   sub-seq-idxs))]
      (if (not-any? #{::invalid} states)
        [sub-seq-idxs states]))))

(defn remove-seq
  [s]
  (map-indexed (fn [index _]
                 (#'clojure.test.check.rose-tree/exclude-nth index s))
               s))

(defn shrink-sequence
  [cmd-seq global-constraint initial-state node-fn]
  (letfn [(shrink-subseq [[indices states]]
            (when (seq indices)
              (rose/make-rose
                (node-fn states (map (comp second (partial get cmd-seq)) indices))
                (->> (remove-seq indices)
                     (map (partial valid-sequence? global-constraint initial-state cmd-seq))
                     (filter identity)
                     (map shrink-subseq)))))]
    (shrink-subseq [(range 0 (count cmd-seq))
                    (map last cmd-seq)])))

(defn cmd-seq-helper
  [state command-generators size global-constraint]
  (gen/bind
    (gen/no-shrink
      (gen/one-of (->> command-generators
                       (filter #(precondition % state))
                       (map (fn [generator]
                              (gen/tuple
                                (gen/return generator)
                                (generate generator state)))))))
    (fn [[generator command]]
      (let [new-state (exec generator state command)
            tuple [generator command new-state]]
        (cond
          (not (global-constraint command state new-state)) (cmd-seq-helper new-state command-generators size global-constraint)
          (= 1 size) (gen/return [tuple])
          :else (gen/fmap
                  (partial concat [tuple])
                  (cmd-seq-helper new-state command-generators (dec size) global-constraint)))))))

(defn all-command-generators [gens]
  (every? (partial satisfies? Command) gens))

(defn random-plan-generator
  [from-to initial-state healthy-states command-generators & {:keys [global-constraint]
                                                              :or   {global-constraint (constantly true)}}]
  {:pre [(all-command-generators command-generators)]}
  (gen/bind (gen/no-shrink (gen/tuple (apply gen/choose from-to)
                                      (gen/elements healthy-states)))
            (fn [[num-elements final-state]]
              (gen/bind (cmd-seq-helper initial-state command-generators num-elements global-constraint)
                        (fn [cmd-seq]
                          (let [shrinked (shrink-sequence (vec cmd-seq)
                                                          initial-state
                                                          global-constraint
                                                          (fn [states commands]
                                                            {::initial-state      initial-state
                                                             ::final-state        final-state
                                                             ::states             states
                                                             ::commands           commands
                                                             ::command-generators command-generators}))]
                            (gen/gen-pure shrinked)))))))

(defn commands-from-to [command-generators from-state target-state]
  {:pre [(all-command-generators command-generators)]}
  (map (fn [command]
         (->docker command from-state target-state))
       (mapcat (fn [generator]
                 (move-to generator from-state target-state)) command-generators)))

(defn initial->broken [plan]
  (map (fn [cmd [old-state new-state]]
         (->docker cmd old-state new-state))
       (::commands plan)
       (partition 2 1 (cons (::initial-state plan)
                            (::states plan)))))

(defn broken->final [plan]
  (commands-from-to (::command-generators plan)
                    (last (::states plan))
                    (::final-state plan)))

(defn final->initial [plan]
  (commands-from-to (::command-generators plan)
                    (::final-state plan)
                    (::initial-state plan)))