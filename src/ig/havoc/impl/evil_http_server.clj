(ns ig.havoc.impl.evil-http-server
  (:require
    [ig.havoc.impl.command-generator :as core]
    [clojure.test.check.generators :as gen]
    [clj-http.client :as http-client]))

(def evil-http-server-faults {:infinite-response     :finite-response
                              :random-response       :not-random
                              :empty-response        :not-empty
                              :random-header         :not-random-header
                              :random-content-length :correct-content-length
                              :random-http-status    :correct-http-status})

(def error-codes #{400 401 402 403 404 409 429
                   500 501 502 503 504})

(defn key-with-val [m v]
  (get (clojure.set/map-invert m) v))

(defn evil-http-server-gen [host-port & {:keys [faults possible-error-codes content-length]
                                         :or   {faults               evil-http-server-faults
                                                possible-error-codes error-codes
                                                content-length       (gen/elements [0 1 -1 123213 -123141
                                                                                    (+ Long/MIN_VALUE 23) Long/MIN_VALUE
                                                                                    (- Long/MAX_VALUE 23) Long/MAX_VALUE])}}]
  (let [error-code-gen (if (gen/generator? possible-error-codes)
                         possible-error-codes
                         (gen/elements possible-error-codes))
        content-length-gen (if (gen/generator? content-length)
                             content-length
                             (gen/return content-length))
        this-state (fn [state]
                     (set (get-in state [host-port :http-server :faults])))
        possible-actions (fn [state]
                           (let [current-state (this-state state)]
                             (cond-> (set
                                       (concat
                                         [:random-http-status :random-content-length]
                                         (remove current-state (keys faults))
                                         (vals (select-keys faults current-state))))
                                     (current-state :infinite-response) (disj :random-response :empty-response)
                                     (current-state :random-response) (disj :infinite-response :empty-response)
                                     (current-state :empty-response) (disj :random-response :empty-response))))]
    (reify core/Command
      (precondition [_ state] true)
      (postcondition [_ state {action :action}]
        (let [possible-action (possible-actions state)]
          (possible-action action)))
      (exec [_ state {:keys [action error-code content-length]}]
        (cond-> state
                true (update-in [host-port :http-server :faults]
                                (fn [current-state]
                                  (if (contains? faults action)
                                    (conj (or current-state #{}) action)
                                    (disj current-state (key-with-val faults action)))))
                error-code (assoc-in [host-port :http-server :error-code] error-code)
                content-length (assoc-in [host-port :http-server :content-length] content-length)))
      (generate [_ state]
        (gen/fmap
          (fn [[action error-code content-lenght]]
            (cond-> {:item   host-port
                     :action action}
                    (= :random-http-status action) (assoc :error-code error-code)
                    (= :random-content-length action) (assoc :content-length content-lenght)))
          (gen/no-shrink (gen/tuple (gen/elements (possible-actions state))
                                    error-code-gen
                                    content-length-gen))))
      (move-to [_ from-state target-state]
        (let [[to-add to-remove] (clojure.data/diff (this-state target-state) (this-state from-state))]
          (map (fn [a]
                 {:item           host-port
                  :action         a
                  :error-code     (get-in from-state [host-port :http-server :error-code])
                  :content-length (get-in from-state [host-port :http-server :content-length])})
               (concat to-add
                       (map faults to-remove))))))))

(defmacro def->docker [x]
  `(defmethod core/->docker ~x [cmd# _# new-state#]
     (let [obj# (:item cmd#)
           value# (get-in new-state# [obj# :http-server])]
       {:command   :http-server/set!
        :host-port obj#
        :state     value#})))

(doseq [fault (mapcat identity evil-http-server-faults)]
  (def->docker fault))

(defmethod core/exec! :http-server/set! [_ {:keys [host-port state]}]
  (http-client/post (str "http://localhost:" (second host-port) "/ig-havoc")
                    {:body           (pr-str state)
                     :content-type   :edn
                     :socket-timeout 10000
                     :conn-timeout   10000
                     :accept         :json}))