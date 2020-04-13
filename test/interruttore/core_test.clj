(ns interruttore.core-test
  (:require [clojure.test :refer :all]
            [interruttore.core :as cb])
  (:import
   [java.time Duration LocalDateTime ZoneOffset]))

(defn now
  []
  (LocalDateTime/now ZoneOffset/UTC))

;; testing internal API

(deftest build-exception-map
  (testing "Provided two sequenques for hard and soft failures return a map
of exception -> failure mode"
    (is (= {NullPointerException ::cb/hard-failure
            ArithmeticException ::cb/soft-failure}
          (#'cb/build-exceptions-map
            [NullPointerException]
            [ArithmeticException]))))
  (testing "If the same exception type is provided for hard and soft failures,
hard one wins"
    (is (= {NullPointerException ::cb/hard-failure
            ArithmeticException ::cb/hard-failure}
          (#'cb/build-exceptions-map
            [NullPointerException ArithmeticException]
            [NullPointerException])))))

(def exceptions-map (#'cb/build-exceptions-map
                      [NullPointerException]
                      [ArithmeticException]))

(deftest exception->failure-mode
  (testing "Return the failure mode associated with an exception, soft"
    (is (= ::cb/soft-failure
          (#'cb/exception->failure-mode (ArithmeticException.) exceptions-map))))
  (testing "Return the failure mode associated with an exception, hard"
    (is (= ::cb/hard-failure
          (#'cb/exception->failure-mode (NullPointerException.) exceptions-map))))
  (testing "Return the failure mode associated with an exception, no match"
    (is (nil?
          (#'cb/exception->failure-mode (Exception.) exceptions-map)))))

(deftest circuit-open?
  (testing "Circuit not open when ::closed :)"
    (is (false? (cb/circuit-open? {::cb/status ::cb/closed}))))
  (testing "Circuit not open when ::semi-open"
    (is (false? (cb/circuit-open? {::cb/status ::cb/semi-open}))))
  (testing "Circuit not open when ::status ::open and ::retry-after is expired"
    (is (false? (cb/circuit-open?
                  {::cb/status ::cb/open
                   ::cb/retry-after (.minus (now) (Duration/ofMinutes 1))}))))
  (testing "Circuit open when ::status ::open and ::retry-after not expired"
    (is (true? (cb/circuit-open?
                 {::cb/status ::cb/open
                  ::cb/retry-after (.plus (now) (Duration/ofMinutes 1))})))))

(deftest circuit-next-state
  (testing "Circuit ::closed, result is :ok, circtuit stays ::closed"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-after nil
            ::cb/retry-count 0}
          (#'cb/circuit-next-state {::cb/status ::cb/closed} {::cb/result ::cb/ok}))))
  (testing "Circuit ::closed, first :soft-failure, inc ::retry-count to 1"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-count 1}
          (#'cb/circuit-next-state {::cb/status ::cb/closed} {::cb/result ::cb/soft-failure}))))
  (testing "Circuit ::closed, last :soft-failure, set to ::open"
    (is (= {::cb/status ::cb/open
            ::cb/reason ::cb/max-retries
            ::cb/retry-after 3
            ::cb/max-retries 1
            ::cb/retry-count 2}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {::cb/result ::cb/soft-failure
             ::cb/retry-after 3}))))
  (testing "Circuit ::closed, last :soft-failure, provide reason"
    (is (= {::cb/status ::cb/open
            ::cb/reason :provided-reason
            ::cb/retry-after 3
            ::cb/max-retries 1
            ::cb/retry-count 2}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {::cb/result ::cb/soft-failure
             ::cb/reason :provided-reason
             ::cb/retry-after 3}))))
  (testing "Circuit ::open, result :ok, set to ::semi-open"
    (is (= {::cb/status ::cb/semi-open
            ::cb/retry-after nil
            ::cb/max-retries 1
            ::cb/retry-count 1}
          (#'cb/circuit-next-state
            {::cb/status ::cb/open
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {::cb/result ::cb/ok}))))
  (testing "Circuit ::semi-open, result :ok, set to ::closed"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-after nil
            ::cb/max-retries 1
            ::cb/retry-count 0}
          (#'cb/circuit-next-state
            {::cb/status ::cb/semi-open
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {::cb/result ::cb/ok}))))
  (testing "Circuit ::closed, first :hard-failure, set to ::open"
    (is (= {::cb/status ::cb/open
            ::cb/reason ::cb/hard-failure
            ::cb/retry-after 5
            ::cb/max-retries 3
            ::cb/retry-count 3}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 3
             ::cb/retry-count 1}
            {::cb/result ::cb/hard-failure
             ::cb/retry-after 5}))))
  (testing "Circuit ::closed, first :hard-failure, provide :reason"
    (is (= {::cb/status ::cb/open
            ::cb/reason :provided-reason
            ::cb/retry-after 5
            ::cb/max-retries 3
            ::cb/retry-count 3}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 3
             ::cb/retry-count 1}
            {::cb/result ::cb/hard-failure
             ::cb/reason :provided-reason
             ::cb/retry-after 5}))))
  )

;; testing public API

;; Return based
(defn wrapped-helper
  "This helper should make it easy to simulate return values
  of a wrapped function"
  ([result value]
   (wrapped-helper result value nil))
  ([result value retry-after]
   {::cb/result result
    ::cb/value value
    ::cb/retry-after retry-after}))

(def wrapped (cb/make-circuit-breaker wrapped-helper))

(deftest circuit-breaker-api
  (testing "Circuit closed, result :ok"
    (is (= {::cb/status ::cb/closed
            ::cb/value 1}
           (wrapped ::cb/ok 1))))
  (testing "Circuit closed, result :soft-failure"
    (is (= {::cb/status ::cb/open
            ::cb/reason ::cb/max-retries
            ::cb/retry-after "after"}
           (wrapped ::cb/soft-failure 1 "after"))))
  (testing "Circuit open, next call :ok, status :semi-open"
    (is (= {::cb/status ::cb/semi-open
            ::cb/value 1}
          (do
            (cb/reset wrapped)
            (wrapped ::cb/soft-failure 1 (now)) ;; open the circuit
            (wrapped ::cb/ok 1)))))
  (testing "Circuit semi-open, next call :ok, status :closed"
    (is (= {::cb/status ::cb/closed
            ::cb/value 1}
          (do
            (cb/reset wrapped)
            (wrapped ::cb/soft-failure 1 (now)) ;; open the circuit
            (wrapped ::cb/ok 1) ;; here the status is :semi-open
            (wrapped ::cb/ok 1)))))
  (testing "Circuit closed, result :hard-failure"
    (is (= {::cb/status ::cb/open
            ::cb/reason ::cb/hard-failure
            ::cb/retry-after "after"}
          (do
            (cb/reset wrapped)
            (wrapped ::cb/hard-failure 1 "after")))))
)

;; Exception based
(defn raise-exception
  "This helper should make it easy to simulate exceptions"
  ([value] (raise-exception value nil))
  ([value ex]
   (if (not (nil? ex))
     (throw ex)
     value)))

(def ex-wrapped (cb/make-circuit-breaker
                  raise-exception
                  {:hard-failure-exceptions [NullPointerException]
                   :soft-failure-exceptions [ArithmeticException]}))

(deftest circuit-breaker-exception-api
  (testing "Circuit closed, result :ok"
    (is (= {::cb/status ::cb/closed
            ::cb/value 1}
           (ex-wrapped 1))))
  #_(testing "Circuit closed, result :soft-failure"
    (is (= {:status :open
            :reason :max-retries
            :retry-after "after"}
           (ex-wrapped 1 (ArithmeticException.)))))
  #_(testing "Circuit open, next call :ok, status :semi-open"
    (is (= {:status :semi-open
            :value 1}
          (do
            (cb/reset ex-wrapped)
            (ex-wrapped 1 (ArithmeticException.)) ;; open the circuit
            (ex-wrapped 1)))))
  #_(testing "Circuit semi-open, next call :ok, status :closed"
    (is (= {:status :closed
            :value 1}
          (do
            (cb/reset ex-wrapped)
            (ex-wrapped 1 (ArithmeticException.)) ;; open the circuit
            (ex-wrapped 1) ;; here the status is :semi-open
            (ex-wrapped 1)))))
  #_(testing "Circuit closed, result :hard-failure"
    (is (= {:status :open
            :reason :hard-failure
            :retry-after "after"}
          (do
            (cb/reset ex-wrapped)
            (ex-wrapped 1 (NullPointerException.))))))
)
