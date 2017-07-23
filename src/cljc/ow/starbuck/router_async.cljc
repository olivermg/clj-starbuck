(ns ow.starbuck.router-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]
            [ow.starbuck.protocols :as p]
            [ow.starbuck.routing :as r]))

(defrecord RouterAsync [config ch ctrl-ch]

  p/Component

  (process [this msg]
    ;;; to prevent go-loop from aborting due to exception in routing logic:
    (try
      (r/advance config msg)
      (catch Exception e
        (println "EXCEPTION (ROUTER ASYNC):" e))
      (catch Error e
        (println "ERROR (ROUTER ASYNC):" e))))

  (answer [this resmsg]
    (r/dispatch config resmsg)))

(defn router [config ch]
  (map->RouterAsync {:config config
                     :ch ch
                     :ctrl-ch (a/chan)}))

(defn start [{:keys [ch ctrl-ch] :as this}]
  (go-loop [[msg msg-ch] (a/alts! [ch ctrl-ch])]
    (when-not (or (nil? msg)
                  (= msg-ch ctrl-ch))
      (doseq [resmsg (p/process this msg)]
        (p/answer this resmsg))
      (recur (a/alts! [ch ctrl-ch]))))
  this)

(defn stop [{:keys [ctrl-ch] :as this}]
  (a/put! ctrl-ch ::stop)
  this)
