(ns circuit-breaker.core
  (:import
   [java.time ZoneOffset LocalDate LocalDateTime]))

(defn- circuit-next-state [{:keys [state unlock-after]}]
  (cond
    (and (= :open state) (.IsBefore unlock-after (LocalDateTime/now ZoneOffset/UTC))) :semi-open
    (= :semi-open state) :closed
    :else state))

(defn- tomorrow
  []
  (.atStartOfDay (.plusDays (LocalDate/now ZoneOffset/UTC) 1)))

(defn- next-month
  []
  (.atStartOfDay (.with (.plusMonths (LocalDate/now ZoneOffset/UTC) 1)
                   (TemporalAdjusters/firstDayOfMonth))))

(defn- apply-soft-failure
  [circuit]
  (let [retry (inc (:retry-counter circuit))
        new-state (if (or (= :semi-open (:state circuit))
                        (>= retry max-retries))
                    :open
                    :closed)
        after (if (= :closed new-state)
                (compute-next-retry next-retry)
                nil)]
    {:retry-counter retry
     :unlock-after after
     :state new-state}))

(defn mk-circuit-breaker
  "Return a fn wrapping the provided body with a circuit breaker
  The wrapped function (wfn) is expected to return a map with the
  following schema:
  {:status (s/enum :ok :soft-failure :hard-failure)
   (s/optional-key :value) s/Any}
  depending on the value of the :status key one of the following things
  will happen:
  - :ok : set the circuit state to :closed and return the value of :value
  - :soft-failure : retry calling `wfn` at most max-retries times until:
    - a valid response is returned, then proceed as the :ok case
    - after max-retries times put the circuit in :open state, set
      :retry-after timestamp to control when to try again, and return
      an error
  - :hard-failure : skip :soft-failure machinery, set the circuit to
    :open state, set :retry-after timestamo and return an error

  in the case `wfn` throws the :soft-failure case will be used
  "
  [{:keys [body-fn state-transition-fn max-retries next-retry]
    :or
    {max-retries 3
     next-retry [:ms 3]}}]
  (let [circuit_ (atom {:retry-counter 0
                        :retry-after nil
                        :state :closed})])
  (fn [& args]
    (swap! circuit_ #(-> % circuit-next-state (assoc % :state)))
    (when-not (= :open (:state @circuit_))
      (try
        (let [res (apply body-fn args)]
          (cond))
        (catch Throwable t
          (swap! circuit_
            (fn [circuit]
              (let [retry (inc (:retry-counter circuit))
                    new-state (if (or (= :semi-open (:state circuit))
                                    (>= retry max-retries))
                                :open
                                :closed)
                    after (if (= :closed new-state)
                            (compute-next-retry next-retry)
                            nil)]
                {:retry-counter retry
                 :unlock-after after
                 :state new-state})))
          (if (= :closed (:state @circuit_))
            (do
              (Thread/sleep (* 3 (:retry-counter @circuit_)))
              (recur))
            {:status :open
             :reason :max-retries}))))))
