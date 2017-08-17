(ns ow.starbuck.impl.core-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop go]]))
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            #?(:clj  [clojure.core.async :refer [go-loop go] :as a]
               :cljs [cljs.core.async :as a])
            [ow.starbuck.protocols :as p]
            [ow.starbuck.routing :as r]))

(defrecord ComponentCoreAsync [name in-ch process-fn deliver-result-fn config ;; mandatory
                               result-pub timeout ;; optional
                               ctrl-ch ;; created
                               ]

  p/Component

  (deliver [this msg]
    (a/put! in-ch msg))

  (deliver-sync
    [this msg]
    (go (let [result-pub (or result-pub
                             (throw (ex-info "no result-pub given, cannot deliver synchronously"
                                             {:this this :msg msg})))
              req-id (rand-int 2100000000)
              msg (assoc msg ::request-id req-id)
              rsub (a/sub result-pub req-id (a/promise-chan))
              _ (a/put! in-ch msg)
              [resmsg ch] (a/alts! [rsub (a/timeout (or timeout 30000))])]
          (dissoc resmsg ::request-id)))))

(defn- safe-call [name f & args]
  #?(:clj  (try
             (apply f args)
             (catch Exception e
               (warn "EXCEPTION (COMPONENT ASYNC" name "):" e))
             (catch Error e
               (warn "ERROR (COMPONENT ASYNC" name "):" e)))
     :cljs (try
             (apply f args)
             (catch :default e
               (warn "ERROR (COMPONENT ASYNC" name "):" e)))))

(defn start [{:keys [name in-ch process-fn ctrl-ch deliver-result-fn] :as this}]
  (go-loop [[msg msg-ch] (a/alts! [in-ch ctrl-ch])]
    (when-not (or (nil? msg)
                  (and (= msg-ch ctrl-ch)
                       (= msg ::stop)))
      (debug name "got message:" msg #_(r/printable-msg msg))
      (some->> (safe-call name process-fn this msg) ;; TODO: add some error handling (to inform clients about error)
               (deliver-result-fn this))
      (debug name "delivered result")
      (recur (a/alts! [in-ch ctrl-ch]))))
  this)

(defn stop [{:keys [ctrl-ch] :as this}]
  (a/put! ctrl-ch ::stop)
  this)

(defn- component* [name in-ch process-fn deliver-result-fn & {:keys [config result-pub timeout]}]
  (map->ComponentCoreAsync {:name name
                            :in-ch in-ch
                            :process-fn process-fn
                            :deliver-result-fn deliver-result-fn
                            :config config
                            :result-pub result-pub
                            :timeout timeout
                            :ctrl-ch (a/chan)}))

(defn component [name in-ch out-ch process-fn]
  (component* name in-ch process-fn
              (fn [this resmsg]
                (when out-ch
                  (a/put! out-ch resmsg)))))

(defn echo-component [name in-ch out-ch]
  (component name in-ch out-ch
             (fn [this msg] msg)))

(defn silent-component [name in-ch process-fn]
  (component* name in-ch process-fn
              (constantly nil)))

(defn router [config in-ch & {:keys [name result-pub timeout]
                              :or {name "router"}}]
  (component* name in-ch
              (fn [{:keys [config] :as this} msg]
                (if-not (:ow.starbuck.routing/tunneled? msg)
                  (r/advance config msg)
                  [(dissoc msg :ow.starbuck.routing/tunneled?)]))
              (fn [{:keys [config] :as this} resmsgs]
                (doseq [rm resmsgs]
                  (let [nc (r/get-next-component config rm)]
                    (p/deliver nc rm))))
              :config config
              :result-pub result-pub
              :timeout timeout))

(defn tunnel [out-ch & {:keys [name]
                        :or {name "tunnel"}}]
  (component* name (a/chan)
              (fn [this msg]
                (assoc msg :ow.starbuck.routing/tunneled? true))
              (fn [this resmsg]
                (a/put! out-ch resmsg))))

(defn component-pub [ch]
  (a/pub ch #(peek (:ow.starbuck.routing/component %))))

(defn component-sub [component-pub kw]
  (a/sub component-pub kw (a/chan)))

(defn result-pub [result-ch]
  (a/pub result-ch ::request-id))
