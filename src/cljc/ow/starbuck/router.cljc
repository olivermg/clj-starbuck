(ns ow.starbuck.router
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]
            [ow.starbuck.routing :as r]))

(defrecord Router [config input-ch])

(defn- safe-route [config msg]
  ;;; to prevent go-loop from aborting due to exception in router
  (try
    (r/route config msg)
    (catch Exception e
      (println "EXCEPTION (ROUTING):" e))
    (catch Error e
      (println "ERROR (ROUTING):" e))))

(defn router [config input-ch]
  (map->Router {:config config
                :input-ch input-ch}))

(defn start [{:keys [config input-ch] :as this}]
  (go-loop [msg (a/<! input-ch)]
    (when-not (or (nil? msg)
                  (= msg ::stop))
      (safe-route config msg)
      (recur (a/<! input-ch))))
  this)

(defn stop [{:keys [input-ch] :as this}]
  (a/put! input-ch ::stop)
  this)
