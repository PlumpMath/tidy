# tomthought/tidy

[![Current Version](https://img.shields.io/clojars/v/tidy.svg)](https://clojars.org/tidy)
[![Circle CI](https://circleci.com/gh/tomthought/tidy.svg?style=shield)](https://circleci.com/gh/tomthought/tidy)
[![Dependencies Status](https://jarkeeper.com/tomthought/tidy/status.svg)](https://jarkeeper.com/tomthought/tidy)

Concurrency patterns using Clojure core.async.

## Usage

```clojure

;; examples taken from dev/dev.clj

(:require [tidy.core :as tidy]
          [clojure.core.async :as async])

;; test an interval-pipe

(let [n 10
      from (async/chan)
      to (async/chan)]

  ;; create an interval pipe with interval of 1s
  (tidy/interval-pipe from to 1000)

  ;; dump messages onto from channel
  (async/go
    (dotimes [i n]
      (async/>! from i)))

  ;; consume messages from to channel (every 1000ms)
  (dotimes [i n]
    (println (async/<!! to)))

  ;; cleanup
  (async/close! from)
  (async/close! to)

  (println "test complete."))
  


;; test an interval pub

(let [n 20
      topic-fn first
      from (async/chan)
      to-foo (async/chan)
      to-bar (async/chan)
      p (tidy/interval-pub from topic-fn)
      foo-sub (async/sub p :foo to-foo)
      bar-sub (async/sub p :bar to-bar)
      log-ch (async/chan)

      c (atom 0)]

  (async/go
    (try (loop []
           (when-let [msg (async/<! log-ch)]
             (println msg)
             (when (= (swap! c inc) n)
               (async/unsub p :foo to-foo)
               (async/unsub p :bar to-bar)
               (async/close! from)
               (async/close! to-foo)
               (async/close! to-bar)
               (async/close! log-ch))
             (recur)))
         (catch Exception e
           (println e))))

  ;; dump messages onto from channel
  (async/go
    (dotimes [i n]
      (async/>! from
                [(if (zero? (mod i 2))
                   :foo
                   :bar)
                 i])))
  
  ;; consume messages from sub channel (every 1000ms)
  (async/go
    (loop []
      (when-let [v (async/<! to-foo)]
        (async/>! log-ch v)
        (recur))))

  (async/<!!
   (async/go
     (loop []
       (when-let [v (async/<! to-bar)]
         (async/>! log-ch v)
         (recur)))))

  (println "test complete."))


```

## License

Copyright © 2017 Tom Goldsmith

Distributed under the Eclipse Public License 1.0.
