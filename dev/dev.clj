(ns dev
  (:require [tidy.core :as tidy]
            [clojure.core.async :as async]))

(defn logger
  []
  (let [log-ch (async/chan)]
    (async/go
      (try (loop []
             (when-let [msg (async/<! log-ch)]
               (println msg)
               (recur)))
           (catch Exception e
             (println e))))
    log-ch))

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

      ;; consume messages from to channel (every 1000ms)
      (dotimes [i n]
        (println (async/<!! to)))

      ;; cleanup
      (async/close! from)
      (async/close! to)

      (println "test complete."))))

(defn interval-pub-test
  []
  (async/thread
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

      (println "test complete."))))

(defn feeder-pipe-test
  []

  (async/thread
    (try (let [n 1000
               from (async/chan (long (Math/pow 2 16)))
               to (async/chan)

               c (atom 0)

               log-ch (logger)

               key-gen (fn [i] (< i (/ n 2)))]

           ;; create an interval pipe with interval of 1s
           (tidy/feeder-pipe from to first n)

           ;; dump messages onto from channel
           (dotimes [i n]
             (async/>!! from [(key-gen i) i]))

           (dotimes [_ 2]
             (async/go

               (try (loop []
                      (when-let [v (async/<! to)]
                        (async/>! log-ch v)
                        (if (= n (swap! c inc))
                          (do (async/close! from)
                              (async/close! to)
                              (async/>! log-ch "test complete.")
                              (async/close! log-ch)))
                        (recur)))
                    (catch Exception e
                      (async/>! log-ch e)))))

           )
         (catch Exception e
           (println e)))))
