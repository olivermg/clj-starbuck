(ns ow.starbuck.component
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]
            [ow.starbuck.routing :as r]))

(defrecord Component [name input-pub input-sub output-ch process-fn])

(defn- safe-process [process-fn msg]
  ;;; to prevent go-loop from aborting due to exception in component
  (try
    (process-fn msg)
    (catch Exception e
      (println "EXCEPTION (COMPONENT):" e)
      #_(assoc msg :ow.starbuck.routing/error e) ;; TODO: offer default error handling?
      )
    (catch Error e
      (println "ERROR (COMPONENT):" e)
      #_(assoc msg :ow.starbuck.routing/error e) ;; TODO: offer default error handling?
      )))

(defn component [name input-pub output-ch process-fn]
  (map->Component {:name name
                   :input-pub input-pub
                   :input-sub (a/sub input-pub name (a/chan))
                   :output-ch output-ch
                   :process-fn process-fn}))

(defn start [{:keys [input-sub output-ch process-fn] :as this}]
  (go-loop [msg (a/<! input-sub)]
    (when-not (or (nil? msg)
                  (= msg ::stop))
      (a/put! output-ch (safe-process process-fn msg))
      (recur (a/<! input-sub))))
  this)

(defn stop [{:keys [input-sub] :as this}]
  (a/put! input-sub ::stop)
  this)
