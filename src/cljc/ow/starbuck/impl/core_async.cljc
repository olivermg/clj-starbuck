(ns ow.starbuck.impl.core-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.starbuck.protocols :as p]
            [ow.starbuck.routing :as r]))

(defrecord ComponentCoreAsync [name in-ch process-fn deliver-result-fn config
                               ctrl-ch]

  p/Component

  (deliver [this msg]
    (a/put! in-ch msg)))

(defn- safe-call [name f & args]
  ;;; to prevent go-loop from aborting due to exception in process-fn:
  (try
    (apply f args)
    ;;; TODO: offer default exception handling?
    (catch Exception e
      (warn "EXCEPTION (COMPONENT ASYNC" name "):" e))
    (catch Error e
      (warn "ERROR (COMPONENT ASYNC" name "):" e))))

(defn start [{:keys [name in-ch process-fn ctrl-ch deliver-result-fn] :as this}]
  (go-loop [[msg msg-ch] (a/alts! [in-ch ctrl-ch])]
    (when-not (or (nil? msg)
                  (and (= msg-ch ctrl-ch)
                       (= msg ::stop)))
      (debug name "got message:" (r/printable-msg msg))
      (->> (safe-call name process-fn this msg)
           (deliver-result-fn this))
      (debug name "delivered result")
      (recur (a/alts! [in-ch ctrl-ch]))))
  this)

(defn stop [{:keys [ctrl-ch] :as this}]
  (a/put! ctrl-ch ::stop)
  this)

(defn- component* [name in-ch process-fn deliver-result-fn & {:keys [config]}]
  (map->ComponentCoreAsync {:name name
                            :in-ch in-ch
                            :process-fn process-fn
                            :deliver-result-fn deliver-result-fn
                            :config config
                            :ctrl-ch (a/chan)}))

(defn component [name in-ch out-ch process-fn]
  (component* name in-ch process-fn
              (fn [this resmsg]
                (a/put! out-ch resmsg))))

(defn router [config in-ch]
  (component* "router" in-ch
              (fn [{:keys [config] :as this} msg]
                (r/advance config msg))
              (fn [{:keys [config] :as this} resmsgs]
                (doseq [rm resmsgs]
                  (-> (r/get-next-component config rm)
                      (p/deliver rm))))
              :config config))

(defn tunnel [out-ch]
  (component* "tunnel" (a/chan)
              (fn [this msg]
                (update msg :ow.starbuck.routing/component pop))
              (fn [this resmsg]
                (a/put! out-ch resmsg))))

(defn component-pub [ch]
  (a/pub ch #(peek (:ow.starbuck.routing/component %))))
