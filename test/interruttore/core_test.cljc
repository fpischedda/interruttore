(ns interruttore.core-test
  (:require #?(:clj  [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [interruttore.core :as cb]))

;; testing internal API
(deftest can-retry-now?
  (testing "Can retry now"
    (is (cb/can-retry-now? (cb/calculate-retry-after -10))))
  (testing "Cannot retry now"
    (is (false? (cb/can-retry-now? (cb/calculate-retry-after 10))))))

(deftest circuit-open?
  (testing "Circuit not open when ::closed :)"
    (is (false? (cb/circuit-open? {::status ::closed}))))
  (testing "Circuit not open when ::semi-open"
    (is (false? (cb/circuit-open? {::status ::semi-open}))))
  (testing "Circuit not open when ::status ::open and ::retry-after is expired"
    (is (false? (cb/circuit-open?
                  {::cb/status ::cb/open
                   ::cb/retry-after (cb/calculate-retry-after -1000)}))))
  (testing "Circuit open when ::status ::open and ::retry-after not expired"
    (is (true? (cb/circuit-open?
                 {::cb/status ::cb/open
                  ::cb/retry-after (cb/calculate-retry-after 1000)})))))

(deftest circuit-next-state
  (testing "Circuit ::closed, result is :ok, circtuit stays ::closed"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-after nil
            ::cb/retry-count 0}
          (#'cb/circuit-next-state {::cb/status ::cb/closed} {:result :ok}))))
  (testing "Circuit ::closed, first :soft-failure, inc ::retry-count to 1"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-count 1}
          (#'cb/circuit-next-state {::cb/status ::cb/closed} {:result :soft-failure}))))
  (testing "Circuit ::closed, last :soft-failure, set to ::open"
    (is (= {::cb/status ::cb/open
            ::cb/last-result :soft-failure
            ::cb/reason :max-retries
            ::cb/retry-after 3
            ::cb/max-retries 1
            ::cb/retry-count 2}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {:result :soft-failure
             :retry-after 3}))))
  (testing "Circuit ::closed, last :soft-failure, provide reason"
    (is (= {::cb/status ::cb/open
            ::cb/last-result :soft-failure
            ::cb/reason :provided-reason
            ::cb/retry-after 3
            ::cb/max-retries 1
            ::cb/retry-count 2}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {:result :soft-failure
             :reason :provided-reason
             :retry-after 3}))))
  (testing "Circuit ::open, result :ok, set to ::semi-open"
    (is (= {::cb/status ::cb/semi-open
            ::cb/retry-after nil
            ::cb/max-retries 1
            ::cb/retry-count 1}
          (#'cb/circuit-next-state
            {::cb/status ::cb/open
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {:result :ok}))))
  (testing "Circuit ::semi-open, result :ok, set to ::closed"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-after nil
            ::cb/max-retries 1
            ::cb/retry-count 0}
          (#'cb/circuit-next-state
            {::cb/status ::cb/semi-open
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {:result :ok}))))
  (testing "Circuit ::closed, first :hard-failure, set to ::open"
    (is (= {::cb/status ::cb/open
            ::cb/last-result :hard-failure
            ::cb/reason :hard-failure
            ::cb/retry-after 5
            ::cb/max-retries 3
            ::cb/retry-count 3}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 3
             ::cb/retry-count 1}
            {:result :hard-failure
             :retry-after 5}))))
  (testing "Circuit ::closed, first :hard-failure, provide :reason"
    (is (= {::cb/status ::cb/open
            ::cb/last-result :hard-failure
            ::cb/reason :provided-reason
            ::cb/retry-after 5
            ::cb/max-retries 3
            ::cb/retry-count 3}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 3
             ::cb/retry-count 1}
            {:result :hard-failure
             :reason :provided-reason
             :retry-after 5}))))
  )

;; testing public API
(defn wrapped-helper
  "This helper should make it easy to simulate return values
  of a wrapped function"
  ([result value]
   (wrapped-helper result value nil))
  ([result value retry-after]
   {:result result
    :value value
    :retry-after retry-after}))

(def wrapped (cb/make-circuit-breaker wrapped-helper
               {:max-retries 1}))

(deftest circuit-breaker-api
  (testing "Circuit closed, result :ok"
    (is (= {:status :closed
            :result :ok
            :value 1}
           (wrapped :ok 1))))
  (testing "Circuit closed, result :soft-failure"
    (is (= {:status :closed
            :result :soft-failure
            :value 1}
           (wrapped :soft-failure 1 "after"))))
  (testing "Circuit open, next call :ok, status :semi-open"
    (is (= {:status :semi-open
            :result :ok
            :value 1}
          (do
            (cb/reset wrapped)
            (wrapped :soft-failure 1)
            ;; open the circuit but wait 0 milliseconds
            ;; before next retry (just for testing)
            (wrapped :soft-failure 1 (cb/calculate-retry-after -1))
            (wrapped :ok 1)))))
  (testing "Circuit semi-open, next call :ok, status :closed"
    (is (= {:status :closed
            :result :ok
            :value 1}
          (do
            (cb/reset wrapped)
            (wrapped :soft-failure 1)
            ;; open the circuit but wait 0 milliseconds
            ;; before next retry (just for testing)
            (wrapped :soft-failure 1 (cb/calculate-retry-after -1))
            (wrapped :ok 1) ;; here the status is :semi-open
            (wrapped :ok 1)))))
  (testing "Circuit closed, fail too many times, status :open"
    (let [retry-after (cb/calculate-retry-after 10)]
      (is (= {:status :open
              :reason :max-retries
              :result :soft-failure
              :retry-after retry-after}
            (with-redefs [cb/calculate-retry-after (fn [_] retry-after)]
              (do
                (cb/reset wrapped)
                (wrapped :soft-failure 1)
                ;; open the circuit
                (wrapped :soft-failure 1)))))))
  (testing "Circuit closed, result :hard-failure"
    (let [retry-after (cb/calculate-retry-after 1000)]
      (is (= {:status :open
              :result :hard-failure
              :reason :hard-failure
              :retry-after retry-after}
            (do
              (cb/reset wrapped)
              (wrapped :hard-failure 1 retry-after))))))
)

;; Custom exception handling

#?(:clj
   (do
     (defn to-be-wrapped [a b] (/ a b))

     (def with-ex
       (cb/make-circuit-breaker
         (fn [a b] {:result :ok
                    :value (to-be-wrapped a b)})
         {:exception-types #{ArithmeticException AssertionError}
          :max-retries 1}))

     (deftest exception-handling
       (testing "Circuit re-throw unhandled exceptions"
         (is (thrown? NullPointerException (with-ex nil 1))))
       (testing "Circuit catches the correct exception type"
         (let [retry-after (cb/calculate-retry-after 1000)]
           (is (= {:status :open
                   :result :soft-failure
                   :reason :max-retries
                   :retry-after retry-after}
                 (with-redefs [cb/calculate-retry-after (fn [_] retry-after)]
                   (do
                     (with-ex 1 0)
                     (with-ex 1 0)))))))))
   :cljs
   (do
     (defn to-be-wrapped [a]
       (case a
         :catched (throw (js/RangeError.))
         :not-catched (throw (js/TypeError.))
         :else a))

     (def with-ex
       (cb/make-circuit-breaker
         (fn [a] {:result :ok
                  :value (to-be-wrapped a)})
         {:exception-types #{js/RangeError}
          :max-retries 1}))

     (deftest exception-handling
       (testing "Circuit re-throw unhandled exceptions"
         (is (thrown? js/TypeError (with-ex :not-catched))))
       (testing "Circuit catches the correct exception type"
         (let [retry-after (cb/calculate-retry-after 1000)]
           (is (= {:status :open
                   :result :soft-failure
                   :reason :max-retries
                   :retry-after retry-after}
                 (with-redefs [cb/calculate-retry-after (fn [_] retry-after)]
                   (do
                     (with-ex :catched)
                     (with-ex :catched)))))))))
   )
