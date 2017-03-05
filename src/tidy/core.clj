(ns tidy.core
  (:require [clojure.core.async :as async]

            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

;;; Declarations

(def ^:private default-interval-ms 1000)
(def ^:private channel-type (type (async/chan)))

;;; API

(defn channel?
  "Returns true when 'x' is a core.async channel instance."
  [x]
  (instance? channel-type x))

(defn interval-pipe
  "Places items from the 'from' channel onto the 'to' channel every
  't' ms from the last hand off."
  ([from to] (interval-pipe from to default-interval-ms))
  ([from to t]
   (assert (pos? t))
   (async/go
     (loop [last-hand-off-ms (t/minus (t/now) (t/millis t))]
       (when-let [v (async/<! from)]
         (let [t* (- (+ (tc/to-long last-hand-off-ms) t)
                     (tc/to-long (t/now)))]
           (async/<! (async/timeout t*))
           (async/>! to v)
           (recur (t/now)))))
     (async/close! to))))

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

(defn feeder-pipe
  "Move messages from 'from' to 'to', subject to being grouped by function 'f'.
  Messages will be placed into a queue with messages that produce the same
  result from 'f'. The queues will then be read, one at a time,
  non-deterministically and the items will be placed onto the 'to' channel."
  ([from to f] (feeder-pipe from to f 1024))
  ([from to f buf-size]
   (let [silos (atom {})
         stop-ch (async/chan)]

     ;; place items from 'from' channel onto the corresponding silo.
     (async/go
       (try (loop []
              (when-let [v (async/<! from)]
                (let [k (f v)]
                  (swap! silos
                         (fn [silos*]
                           (let [ch (or (get silos* k) (async/chan buf-size))]
                             (async/put! ch v)
                             (assoc silos* k ch))))
                  (recur))))
            (catch Exception e
              (println e)))
       (doseq [ch (vals @silos)]
         (async/close! ch))
       (async/close! stop-ch)
       (async/close! to))

     ;; read non-deterministically from 'silos', placing results on 'to' channel.
     (async/go
       (try (loop []
              (let [[v ch] (async/alts!
                            (vec
                             (concat
                              [stop-ch (async/timeout 100)]
                              (vals @silos))))]
                (when (not= ch stop-ch)
                  (when (and v ch) (async/>! to v))
                  (recur))))
            (catch Exception e
              (println e)))))))

;;; Private
