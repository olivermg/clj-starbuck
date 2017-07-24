(ns ow.starbuck.components.core-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.starbuck.protocols :as p]))

(defrecord ComponentCoreAsync [name in-pub in-ch out-ch process-fn ctrl-ch]

  p/Component

  (process [this msg]
    (a/put! in-ch msg)))

(defn component [name in-pub out-ch process-fn]
  (map->ComponentCoreAsync {:name name
                            :in-pub in-pub
                            :in-ch (a/sub in-pub name (a/chan))
                            :out-ch out-ch
                            :process-fn process-fn
                            :ctrl-ch (a/chan)}))

(defn- safe-process [name process-fn msg]
  ;;; to prevent go-loop from aborting due to exception in process-fn:
  (try
    (process-fn msg)
    (catch Exception e
      (println "EXCEPTION (COMPONENT ASYNC" name "):" e))
    (catch Error e
      (println "ERROR (COMPONENT ASYNC" name "):" e))))

(defn start [{:keys [name in-ch out-ch process-fn ctrl-ch] :as this}]
  (go-loop [[msg msg-ch] (a/alts! [in-ch ctrl-ch])]
    (when-not (or (nil? msg)
                  (= msg-ch ctrl-ch))
      (some->> (safe-process name process-fn msg)
               (a/put! out-ch))
      (recur (a/alts! [in-ch ctrl-ch]))))
  this)

(defn stop [{:keys [ctrl-ch] :as this}]
  (a/put! ctrl-ch ::stop)
  this)
