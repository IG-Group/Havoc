(ns ig.havoc-test
  (:require [clojure.test :refer :all]
            [ig.havoc.core :as api]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]))

(deftest revert
  (is (= #{:container/start}
         (set (map :command
                   (api/commands-from-to [(api/container-gen :foo)]
                                         {:foo {:status :stopped}}
                                         {:foo {:status :ok}})))))

  (is (= #{}
         (set (map :command
                   (api/commands-from-to [(api/link-handicaps-gen [:foo :bar])]
                                         {}
                                         {})))))

  (is (= [{:command :link/flaky
           :from    :foo
           :to      :bar
           :corrupt {:percent 5}}]
         (api/commands-from-to [(api/link-handicaps-gen [:foo :bar])]
                               {}
                               {[:foo :bar] {:handicaps #{:link/corrupt}}}))))

(def cmds :ig.havoc.impl.command-generator/commands)

(deftest not-two-same-consecutive-commands
  (checking "not-two-same-consecutive-commands" 100
    [a (gen/not-empty (gen/vector-distinct gen/keyword))]
    (is (every?
          (fn [{cmd-seq cmds}]
            (and
              (<= 4 (count cmd-seq) 10)
              (let [without-restart (remove (comp (partial = :container/restart) :action) cmd-seq)]
                (= (count without-restart)
                   (count (partition-by identity without-restart))))))
          (gen/sample (api/random-plan-generator [4 10]
                                                 (zipmap a (repeat {:status :ok}))
                                                 [(zipmap a (repeat {:status :ok}))]
                                                 (mapcat (fn [x] [(api/container-gen x)]) a)))))))

(defn at-least-one-ok [new-state]
  (some
    (fn [[_ {:keys [status]}]]
      (= :ok status))
    new-state))

(defn generate-rose [initial-state commands]
  (let [r (random/make-random)
        size-seq (cycle (range 0 10))]
    (gen/call-gen (api/random-plan-generator [4 10]
                                             initial-state
                                             [initial-state]
                                             commands
                                             :global-constraint (fn [_ _ new-state]
                                                                  (at-least-one-ok new-state)))
                  (first (gen/lazy-random-states r))
                  size-seq)))

(defn valid-rose-tree? [rose-tree]
  (let [root (rose/root rose-tree)]
    (and
      (seq (cmds root))
      (every? at-least-one-ok (:ig.havoc.impl.command-generator/states root))
      (> (count (cmds root))
         (count (cmds (rose/children rose-tree))))
      (every?
        (fn [child-tree]
          (empty? (reduce (fn [child-commands parent-command]
                            (if (= (first child-commands) parent-command)
                              (rest child-commands)
                              child-commands))
                          (cmds (rose/root child-tree))
                          (cmds root))))
        (rose/children rose-tree))
      (every? valid-rose-tree? (rose/children rose-tree)))))


(deftest shrinking
  (checking "shrinking" 10
    [a (gen/such-that (fn [v] (<= 3 (count v)))
                      (gen/vector-distinct gen/keyword))]
    (is (let [rose-tree (generate-rose (zipmap a (repeat {:status :ok}))
                                       (map #(api/container-gen %) a))]
          (valid-rose-tree? rose-tree)))))


(deftest one-ok
  (let [one-ok-always (api/keep-some-containers-ok 2 [:foo :bar :baz])]
    (are [expected action old-state]
      (= expected (one-ok-always action old-state nil))

      false
      {:action :container/restart
       :item   :foo}
      {:foo {:status :ok}
       :bar {:status :stopped}
       :baz {:status :stopped}}

      true
      {:action :container/restart
       :item   :foo}
      {:foo {:status :ok}
       :bar {:status :ok}
       :baz {:status :ok}}

      true
      {:action :container/restart
       :item   :foo}
      {:foo {:status :stopped}
       :bar {:status :ok}
       :baz {:status :stopped}})))