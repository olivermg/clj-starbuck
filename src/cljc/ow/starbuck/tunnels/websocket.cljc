(ns ow.starbuck.tunnels.websocket
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

(defrecord WebsocketTunnel [recv-fn    ;; server & client
                            path       ;; client
                            userid-fn  ;; server
                            senteobjs  ;; start/stop
                            ]

  p/Tunnel

  (send [this msg]
    (do-send this msg)))

(defn websocket-tunnel [recv-fn & {:keys [path userid-fn]}]
  (map->WebsocketTunnel {:recv-fn recv-fn
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
