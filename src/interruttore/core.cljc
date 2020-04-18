(ns interruttore.core
  #?(:clj
     (:import
      [java.time Duration LocalDateTime ZoneOffset])))

(defn now
  []
  #?(:clj (LocalDateTime/now ZoneOffset/UTC)
     :cljs (js/Date.)))

(defn circuit-open?
  "Tells if the circuit is open or not; having retry-after set to nil
   means that the circuit will be open until reset."
  [{::keys [status retry-after]}]
  (and (= ::open status)
    (or (nil? retry-after) (.isBefore (now) retry-after))))

(defn calculate-retry-after
  [ms]
  #?(:clj (.plus (now) (Duration/ofMillis ms))
     :cljs (js/Date. (+ ms (.getTime (js/Date.))))))

(comment
  (circuit-open? {::status ::closed}) ;; => false
  (circuit-open? {::status ::semi-open}) ;; => false
  (circuit-open? {::status ::open}) ;; => true
  (circuit-open? {::status ::open
                  ::retry-after (.plus (now) (Duration/ofMinutes 1))}) ;; => true
  (circuit-open? {::status ::open
                  ::retry-after (.minus (now) (Duration/ofMinutes 1))}) ;; => false
  )

(defn- circuit-next-state
  "Compute the next state of the circuit based on the result of the call
  to the wrapped function (`wfn`) which sholud have the following schema:
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
    :open state, set :retry-after timestamp and return an error
  "
  [{::keys [status retry-count retry-after-ms max-retries] :as circuit
    :or    {retry-count 0
            max-retries 3}}
   ;; aliasing _result just for documentation
   {:keys [result retry-after reason] :as _result}]
  (case result
    :ok ;; everything is fine this time!
    ;; but maybe it was not :ok before, so be careful
    (assoc circuit
      ::status (if (= ::open status) ::semi-open ::closed)
      ::retry-after nil
      ::retry-count (if (= ::open status) retry-count 0))

    ;; in case of soft failure check how many times we have alredy failed
    ;; to decide if the circuit have to be opened or if we can try again
    :soft-failure
    (if (>= retry-count max-retries)
      (assoc circuit
        ::status ::open
        ::reason (or reason :max-retries)
        ::last-result result
        ::retry-count (inc retry-count)
        ::retry-after (or retry-after (calculate-retry-after retry-after-ms)))
      (assoc circuit
        ::retry-count (inc retry-count)))

    ;; hard failure, STOP THE WORLD!!!
    ;; setting retry-count to max-retries to support semi-open case
    :hard-failure
    (assoc circuit
      ::status ::open
      ::last-result result
      ::reason (or reason :hard-failure)
      ::retry-count max-retries
      ::retry-after (or retry-after (calculate-retry-after retry-after-ms)))))

(comment
  (circuit-next-state {::status ::closed} {:result :ok})
  (circuit-next-state {::status ::closed} {:result :soft-failure})
  )

(defn make-circuit-breaker
  "Return a fn wrapping the provided wrapped-fn with a circuit breaker.

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
    :open state, set :retry-after timestamp and return an error

  in the case `wfn` throws the :soft-failure case will be used
  "
  ([wrapped-fn]
   (make-circuit-breaker wrapped-fn {}))
  ([wrapped-fn
    {:keys [max-retries retry-after-ms exception-types]
     :or
     {max-retries 3
      retry-after-ms 10
      exception-types #{Throwable}}}]
   (let [circuit_ (atom {::retry-count 0
                         ::retry-after nil
                         ::retry-after-ms retry-after-ms
                         ::max-retries max-retries
                         ::last-result nil
                         ::status ::closed})]
     (with-meta
       (fn [& args]
         (if (circuit-open? @circuit_)
           ;; circuit still open, fail early
           (let [{::keys [last-result retry-after]} @circuit_]
             {:status :open
              :result last-result
              :retry-after retry-after})
           ;; circuit not open, try to call wrapped-fn and handle the result
           (let [{:keys [result value] :as res}
                   (try
                     (apply wrapped-fn args)
                     (catch Throwable t
                       ;; exceptions are like soft-failures but,
                       ;; before we can proceed, we need to be sure that type
                       ;; of the exception is one of those handled by the
                       ;; circuit breaker
                       (if (reduce (fn [_ ex-type] (when (instance? ex-type t)
                                                     true))
                             nil exception-types)
                         {:result :soft-failure}
                         (throw t))))]
               ;; evaluate result and prev state to calculate next state
               (swap! circuit_ #(circuit-next-state % res))

               (let [{::keys [status retry-count retry-after reason]} @circuit_]
                 (if (= ::open status)
                   ;; wrapped-fn failed too many times
                   {:status :open
                    :reason reason
                    :result result
                    :retry-after retry-after}

                   ;; circuit still not open, wrapped-fn may have failed
                   {:status (if (= ::closed status) :closed :semi-open)
                    :result result
                    :value value}
                   )))))
       {:circuit_ circuit_}))))

(defn inspect
  "Return the status of the provided circuit (as an atom)"
  [c]
  (:circuit_ (meta c)))

(defn reset
  "Reset the circuit to initial state"
  [c]
  (swap! (:circuit_ (meta c)) assoc
    ::status ::closed
    ::retry-count 0
    ::retry-after nil))
