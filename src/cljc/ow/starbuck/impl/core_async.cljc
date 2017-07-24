(ns ow.starbuck.impl.core-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.starbuck.protocols :as p]
            [ow.starbuck.routing :as r]))

(defrecord ComponentCoreAsync [in-ch process-fn deliver-result-fn config
                               ctrl-ch]

  p/Component

  (deliver [this msg]
    (a/put! in-ch msg)))

(defn- safe-call [f & args]
  ;;; to prevent go-loop from aborting due to exception in process-fn:
  (try
    (apply f args)
    (catch Exception e
      (println "EXCEPTION (COMPONENT ASYNC):" e))
    (catch Error e
      (println "ERROR (COMPONENT ASYNC):" e))))

(defn start [{:keys [in-ch process-fn ctrl-ch deliver-result-fn] :as this}]
  (go-loop [[msg msg-ch] (a/alts! [in-ch ctrl-ch])]
    (when-not (or (nil? msg)
                  (and (= msg-ch ctrl-ch)
                       (= msg ::stop)))
      (->> (safe-call process-fn this msg)
           (deliver-result-fn this))
      (recur (a/alts! [in-ch ctrl-ch]))))
  this)

(defn stop [{:keys [ctrl-ch] :as this}]
  (a/put! ctrl-ch ::stop)
  this)

(defn- component* [in-ch process-fn deliver-result-fn & {:keys [config]}]
  (map->ComponentCoreAsync {:in-ch in-ch
                            :process-fn process-fn
                            :deliver-result-fn deliver-result-fn
                            :config config
                            :ctrl-ch (a/chan)}))

(defn component [in-ch out-ch process-fn]
  (component* in-ch process-fn
              (fn [this resmsg]
                (a/put! out-ch resmsg))))

(defn router [config in-ch]
  (component* in-ch
              (fn [{:keys [config] :as this} msg]
                (r/advance config msg))
              (fn [{:keys [config] :as this} resmsgs]
                (doseq [rm resmsgs]
                  (-> (r/get-next-component config rm)
                      (p/deliver rm))))
              :config config))

(defn tunnel [out-ch]
  (component* (a/chan)
              (fn [this msg]
                (update msg :ow.starbuck.routing/component pop))
              (fn [this resmsg]
                (a/put! out-ch resmsg))))

(defn component-pub [ch]
  (a/pub ch #(peek (:ow.starbuck.routing/component %))))
