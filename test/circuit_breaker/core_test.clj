(ns circuit-breaker.core-test
  (:require [clojure.test :refer :all]
            [circuit-breaker.core :refer :all]))


(deftest a-test
  (testing "FIXME, I don't fail."
    (is (true? (= 4 (count (str (= 1 1))))))))
