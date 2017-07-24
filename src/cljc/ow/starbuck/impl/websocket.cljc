(ns ow.starbuck.impl.websocket
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [taoensso.sente :as sente]
            #?(:clj  [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]])
            [ow.starbuck.protocols :as p]))

#?(:clj  (defn- make-sente-channel! [{:keys [userid-fn] :as this}]
           (sente/make-channel-socket! (get-sch-adapter)
                                       (merge {}
                                              (and userid-fn
                                                   {:user-id-fn userid-fn}))))

   :cljs (defn- make-sente-channel! [{:keys [path] :as this}]
           (sente/make-channel-socket! path
                                       {:wrap-recv-evs? false})))

(defn- handle-inbound-sentemsg [{:keys [recv-fn] :as this} {:keys [id ?data] :as sentemsg}]
  (debug "got inbound message:" id ?data)
  ;;; TODO: add auth checking
  ;;; TODO: maybe add msg shaping
  (recv-fn ?data))

(defn- start-inbound-msg-handler! [{:keys [senteobjs] :as this}]
  (let [ch-recv (:ch-recv senteobjs)]
    (go-loop [sentemsg (a/<! ch-recv)]
      (when-not (nil? sentemsg)
        (when (not= (namespace (:id sentemsg)) "chsk")
          (handle-inbound-sentemsg this sentemsg))
        (recur (a/<! ch-recv))))))

(defn- do-send [{:keys [senteobjs] :as this} msg]
  ;;; TODO: maintain queue of pending msgs, to make sure they are sent in the correct order
  (go-loop [_ nil]
    (let [open? (some-> senteobjs :state .-state :open?)
          send-fn (:send-fn senteobjs)]
      (if open?
        (do (debug "sending message to remote:" msg)
            (send-fn msg))
        (do (debug "channel not open yet, delaying:" msg)
            (recur (a/<! (a/timeout 500))))))))

(defrecord ComponentWebsocket [recv-fn    ;; server & client
                               path       ;; client
                               userid-fn  ;; server
                               senteobjs  ;; start/stop
                               ]

  p/Component

  (deliver [this msg]
    (do-send this msg)))

(defn tunnel [recv-fn & {:keys [path userid-fn]}]
  (map->ComponentWebsocket {:recv-fn recv-fn
                            :path path
                            :userid-fn userid-fn}))

(defn start [this]
  (let [this (assoc this
                    :senteobjs (make-sente-channel! this))]
    (start-inbound-msg-handler! this)
    this))

(defn stop [this]
  ;;;(a/close! this ch-recv)
  (assoc this :senteobjs nil))

(defn post-fn [{:keys [senteobjs] :as this}]
  (:ajax-post-fn senteobjs))

(defn get-fn [{:keys [senteobjs] :as this}]
  (:ajax-get-or-ws-handshake-fn senteobjs))



(comment (do (require '[ring.middleware.keyword-params :refer [wrap-keyword-params]])
             (require '[ring.middleware.params :refer [wrap-params]])
             (require '[org.httpkit.server :refer [run-server]])

             (def ws1 (-> (websocket-tunnel println) start))

             (def ws1p (post-fn ws1))
             (def ws1g (get-fn ws1))

             (def app (-> (fn [{:keys [uri request-method] :as req}]
                            (case [uri request-method]
                              ["/chsk" :get]  (ws1g req)
                              ["/chsk" :post] (ws1p req)))
                          wrap-keyword-params
                          wrap-params))

             (def srv (run-server app {:port 5556})))

         (def srv (srv))

         )
