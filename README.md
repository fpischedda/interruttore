# interruttore

Interruttore is a [circuit-breaker](http://en.wikipedia.org/wiki/Circuit_breaker_design_pattern) implementation for Clojure/ClojureScript.

Circuit breakers help you write simpler, more robust code in cases where failures might happen (e.g. network or db is down).

If you want to know more about this design pattern have a look at [this awesome blog post](https://www.martinfowler.com/bliki/CircuitBreaker.html) by Martin Fowler.

[![Clojars Project](https://img.shields.io/clojars/v/interruttore.svg)](https://clojars.org/interruttore)

```clj
[interruttore "0.1.3"]

```

Still alpha, interfaces, namespaces, functionality may change
every time; not tested, use at your own risk.

If you need to use a battle tested circuit breaker implementation (and more),
please have a look at [diehard](https://github.com/sunng87/diehard).

## Usage

This library provides two ways to wrap a function with a circuit breaker:
- Exception based
- Based on the return value of the wrapped function (richer API)

### Exception based example

``` clj

(require '[interruttore.core :as cb])

(defn to-be-wrapped [a b]
  {:result :ok
   :value (/ a b)})

(def wrapped (cb/make-circuit-breaker to-be-wrapped {:max-retries 1
                                                     :exceptions [ArithmeticException]
                                                     :retry-after-ms 10}))

;; happy case
(wrapped 2 2) ;; => {:status :closed :value 1}

;; failing case, will return a failure, internally it will keep track of the
;; count of the failures, but at this time the circuit is still closed
(wrapped 2 0) ;; => {:status :closed :result :soft-failure}

;; after this failure the circuit is in the open state
(wrapped 2 0) ;; => {:status :open
              ;;     :reason :max-retries
              ;;     :result :soft-failure
              ;;     :retry-after (now-plus-10-ms)}

;; wrapped start to work again, we are in the semi-open state now
(wrapped 4 2) ;; => {:status :semi-open :result :ok :value 2}

;; fail again, but this time the circuit will be opened right after this call
;; instead of waiting for another failure
(wrapped 2 0) ;; => {:status :open
              ;;     :reason :max-retries
              ;;     :retry-after (now-plus-10-ms)}

```

### Return based example

``` clj

(defn to-be-wrapped [result]
  result)

(def wrapped (cb/make-circuit-breaker to-be-wrapped {:max-retries 1
                                                     :retry-after-ms 10}))

;; happy case
(wrapped {:result :ok :value 1}) ;; => {:status :closed :result :ok :value 1}

;; failing case, retry max-retry times and wait retry-after-ms before retrying
(wrapped {:result :soft-failure
          :retry-after (in-a-minute)}) ;; => {:status :closed
                                       ;;     :result :soft-failure}
(wrapped {:result :soft-failure
          :retry-after (in-a-minute)}) ;; => {:status :open
                                       ;;     :result :soft-failure
                                       ;;     :reason :max-retries
                                       ;;     :retry-after (in-a-minute)}

;; wrapped start to work again, we are in the semi-open state now
(wrapped {:result :ok :value 2}) ;; => {:status :semi-open :result :ok :value 2}

;; fail again, but this time it opens the circuit right after the call
(wrapped {:result :soft-failure
          :retry-after (in-a-minute)}) ;; => {:status :open
                                       ;;     :result :ok
                                       ;;     :reason :max-retries
		                               ;;     :retry-after (in-a-minute)}

```

Return based approach, so far, seems very close to the exception based one,
if we exclude the possibility, for the wrapped function, to tell exactly until
when the circuit must stay open; this method provides an additional result
key :hard-failure which will open the circuit without retrying to call the
wrapped function.
This can be convenient, for example, in case an external API has a quota;
it does not make any sense to call the external API again today if we have
exceed our daily quota.

``` clj

(cb/reset wrapped)  ;; reset the circuit to its initial state

;; fail hard this time, so it does not retry
(wrapped {:result :hard-failure
          :retry-after (tomorrow)}) ;; => {:status :open
                                    ;;     :result :hard-failure
                                    ;;     :reason :hard-failure
		                            ;;     :retry-after :some-date-time}

```

The map returned by the wrapped can provide another key, `:reason`, that can be
used to replace the builtin `:reason` returned when the circuit is open, here is
an example:

``` clj

(cb/reset wrapped)  ;; reset the circuit to its initial state

;; fail hard, this time providing a :reason
(wrapped {:result :hard-failure
          :reason :daily-quota-exceeded
          :retry-after (tomorrow)}) ;; => {:status :open
                                    ;;     :result :hard-failure
                                    ;;     :reason :daily-quota-exceeded
		                            ;;     :retry-after :some-date-time}

```

## TODO

- get feedback: is it useful? missing something?
  received some very useful feedback; open for the next round.
- put everything on clojars
- better documentation
- def-circuit-breaker macro?
- support to ClojureScript (should require few changes exp datetime handling)

## License

Copyright © 2020 Francesco Pischedda

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
