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
   (let [inner-pub-ch (async/chan)
         interval-channels (atom {})
         p (async/pub inner-pub-ch topic-fn buf-fn)
         topic-ch (fn [v]
                    (when-let [topic (topic-fn v)]
                      (or (get @interval-channels topic)
                          (let [from-ch (async/chan)]
                            (interval-pipe from-ch inner-pub-ch t)
                            (swap! interval-channels assoc topic from-ch)
                            from-ch))))

         unsub-all! (fn [topic]
                      (doseq [[topic* interval-ch] @interval-channels]
                        (when (or (nil? topic) (= topic topic*))
                          (async/close! interval-ch)
                          (swap! interval-channels dissoc topic*))))

         p* (reify
              async/Mux
              (muxch* [_] (async/muxch* p))
              async/Pub
              (sub* [_ topic ch close?]
                (async/sub* p topic ch close?))
              (unsub* [_ topic ch]
                (async/unsub* p topic ch))
              (unsub-all* [_]
                (unsub-all! nil)
                (async/unsub-all* p))
              (unsub-all* [_ topic]
                (unsub-all! topic)
                (async/unsub-all* p topic)))]

     (async/go
       (loop []
         (when-let [v (async/<! ch)]
           (when-let [ch* (topic-ch v)]
             (async/>! ch* v))
           (recur)))
       (unsub-all!)
       (async/close! inner-pub-ch))

     p*)))

;;; Private
