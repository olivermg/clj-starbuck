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
      (debug name "got message:" msg #_(r/printable-msg msg))
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

(defn echo-component [name in-ch out-ch]
  (component name in-ch out-ch
             (fn [this msg] msg)))

(defn silent-component [name in-ch process-fn]
  (component* name in-ch process-fn
              (constantly nil)))

(defn router [config in-ch & {:keys [name]
                              :or {name "router"}}]
  (component* name in-ch
              (fn [{:keys [config] :as this} msg]
                (if-not (::tunneled? msg)
                  (r/advance config msg)
                  [(dissoc msg ::tunneled?)]))
              (fn [{:keys [config] :as this} resmsgs]
                (doseq [rm resmsgs]
                  (-> (r/get-next-component config rm)
                      (p/deliver rm))))
              :config config))

(defn tunnel [out-ch & {:keys [name]
                        :or {name "tunnel"}}]
  (component* name (a/chan)
              (fn [this msg]
                (assoc msg ::tunneled? true))
              (fn [this resmsg]
                (a/put! out-ch resmsg))))

(defn component-pub [ch]
  (a/pub ch #(peek (:ow.starbuck.routing/component %))))

(defn component-sub [component-pub kw]
  (a/sub component-pub kw (a/chan)))

(defn result-pub [result-ch]
  (a/pub result-ch ::request-id))

(defn process-message [router-ch msg]
  (a/put! router-ch msg))

(defn process-message-sync [router-ch result-pub msg & {:keys [timeout]}]
  (let [req-id (rand-int Integer/MAX_VALUE)
        msg (assoc msg ::request-id req-id)]
    (a/put! router-ch msg)
    (let [rsub (a/sub result-pub req-id (a/promise-chan))
          [resmsg ch] (a/alts!! [rsub (a/timeout (or timeout 20000))])]
      (dissoc resmsg ::request-id))))
