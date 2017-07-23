(ns ow.starbuck.component-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]
            [ow.starbuck.protocols :as p]))

(defrecord ComponentAsync [name in-ch out-ch process-fn ctrl-ch]

  p/Component

  (process [this msg]
    (a/put! in-ch msg)))

(defn component [name in-ch out-ch process-fn]
  (map->ComponentAsync {:name name
                        :in-ch in-ch
                        :out-ch out-ch
                        :process-fn process-fn
                        :ctrl-ch (a/chan)}))

(defn- safe-process [process-fn msg]
  ;;; to prevent go-loop from aborting due to exception in process-fn:
  (try
    (process-fn msg)
    (catch Exception e
      (println "EXCEPTION (COMPONENT):" e))
    (catch Error e
      (println "ERROR (COMPONENT):" e))))

(defn start [{:keys [in-ch out-ch process-fn ctrl-ch] :as this}]
  (go-loop [[msg msg-ch] (a/alts! [in-ch ctrl-ch])]
    (when-not (or (nil? msg)
                  (= msg-ch ctrl-ch))
      (some->> (safe-process process-fn msg)
               (a/put! out-ch))
      (recur (a/alts! [in-ch ctrl-ch]))))
  this)

(defn stop [{:keys [ctrl-ch] :as this}]
  (a/put! ctrl-ch ::stop)
  this)
