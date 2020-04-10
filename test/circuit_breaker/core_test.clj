(ns circuit-breaker.core-test
  (:require [clojure.test :refer :all]
            [circuit-breaker.core :as cb])
  (:import
   [java.time Duration LocalDateTime ZoneOffset]))

(defn now
  []
  (LocalDateTime/now ZoneOffset/UTC))

(deftest circuit-open?
  (testing "Circuit not open when ::closed :)"
    (is (false? (cb/circuit-open? {::status ::closed}))))
  (testing "Circuit not open when ::semi-open"
    (is (false? (cb/circuit-open? {::status ::semi-open}))))
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
          (#'cb/circuit-next-state {::cb/status ::cb/closed} {:result :ok}))))
  (testing "Circuit ::closed, first :soft-failure, inc ::retry-count to 1"
    (is (= {::cb/status ::cb/closed
            ::cb/retry-after nil
            ::cb/retry-count 1}
          (#'cb/circuit-next-state {::cb/status ::cb/closed} {:result :soft-failure}))))
  (testing "Circuit ::closed, last :soft-failure, set to ::open"
    (is (= {::cb/status ::cb/open
            ::cb/retry-after 3
            ::cb/max-retries 1
            ::cb/retry-count 2}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 1
             ::cb/retry-count 1}
            {:result :soft-failure
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
            ::cb/retry-after 5
            ::cb/max-retries 3
            ::cb/retry-count 3}
          (#'cb/circuit-next-state
            {::cb/status ::cb/closed
             ::cb/max-retries 3
             ::cb/retry-count 1}
            {:result :hard-failure
             :retry-after 5}))))
  )
