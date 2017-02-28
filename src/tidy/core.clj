(ns tidy.core
  (:require [clojure.core.async :as async]

            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

;;; Declarations

(def ^:private default-interval-ms 1000)

;;; API

(defn interval-pipe
  "Places items from the 'from' channel onto the 'to' channel every
  't' ms from the last hand off."
  ([from to] (interval-pipe from to default-interval-ms))
  ([from to t]
   (async/go
     (loop [last-hand-off-ms (t/now)
            q []]
       (let [t* (- (+ (tc/to-long last-hand-off-ms) t) (tc/to-long (t/now)))
             [v ch] (async/alts! [(async/timeout t*) from])]
         (if-not (= ch from)
           (if-let [v (first q)]
             (do (async/>! to v)
                 (recur (t/now) (vec (rest q))))
             (recur (t/now) q))
           (when v (recur last-hand-off-ms (conj q v)))))))))

(defn interval-pub
  "Works the same as a core.async 'pub', with the added functionality that no
  single subscription will receive a message more frequently than every 't' ms."
  ([ch topic-fn] (interval-pub ch topic-fn default-interval-ms))
  ([ch topic-fn t] (interval-pub ch topic-fn t (constantly nil)))
  ([ch topic-fn t buf-fn]


   ))

;;; Private
