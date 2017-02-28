(ns dev
  (:require [tidy.core :as tidy]
            [clojure.core.async :as async]))

(defn interval-test
  []
  (async/thread
    (let [n 10
          from (async/chan)
          to (async/chan)]

      ;; create an interval pipe with interval of 1s
      (tidy/interval-pipe from to 1000)

      ;; dump messages onto from channel
      (async/go
        (dotimes [i n]
          (async/>! from i)))

      ;; consume message from to channel
      (dotimes [i n]
        (println (async/<!! to)))

      ;; cleanup
      (async/close! from)
      (async/close! to)

      (println "test complete."))))
