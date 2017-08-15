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
                                              (and userid-fn {:user-id-fn userid-fn}))))

   :cljs (defn- make-sente-channel! [{:keys [path] :as this}]
           (sente/make-channel-socket! path
                                       {:wrap-recv-evs? false})))

#?(:clj  (defn- do-send* [{:keys [senteobjs userid-kw] :as this} msg]
           (let [userid (get msg userid-kw)
                 msg (dissoc msg userid-kw)
                 send-fn (:send-fn senteobjs)]
             (debug "sending message to remote id" userid ":" msg)
             (send-fn userid [::message msg])))

   :cljs (defn- do-send* [{:keys [senteobjs] :as this} msg]
           (go-loop [[i _] [0 nil]]
             (if (< i 100)
               (if (some-> senteobjs :state .-state :open?)
                 (let [send-fn (:send-fn senteobjs)]
                   (debug "sending message to remote:" msg "(try" i ")")
                   (send-fn [::message msg]))
                 (do (debug "channel not open yet, delaying (try" i "):" msg)
                     (recur [(inc i) (a/<! (a/timeout 500))])))
               (warn "could not send message after 100 tries, aborting:" msg)))))

(defn- handle-inbound-sentemsg [{:keys [recv-fn userid-fn userid-kw] :as this}
                                {:keys [id ?data ring-req] :as sentemsg}]
  (let [userid (and userid-fn (userid-fn ring-req))
        ?data (merge ?data
                     (and userid {userid-kw userid}))]
    (debug "got message from remote:" id ?data)
    ;;; TODO: add auth checking
    ;;; TODO: maybe add msg shaping
    (case id
      ::message (recv-fn ?data)
      (warn "unknown message id" id))))

(defn- start-inbound-msg-handler! [{:keys [senteobjs] :as this}]
  (let [ch-recv (:ch-recv senteobjs)]
    (go-loop [sentemsg (a/<! ch-recv)]
      (when-not (nil? sentemsg)
        (when (not= (namespace (:id sentemsg)) "chsk")
          (handle-inbound-sentemsg this sentemsg))
        (recur (a/<! ch-recv))))))

(defn- do-send [this msg]
  ;;; TODO: maintain queue of pending msgs, to make sure they are sent in the correct order
  (let [msg (assoc msg :ow.starbuck.routing/tunneled? true)]
    (do-send* this msg)))

(defrecord ComponentWebsocket #?(:clj  [recv-fn           ;; fn to call for incoming msg
                                        userid-fn         ;; fn that gets userid from ring request
                                        userid-kw         ;; kw that userid will be assocd to in msg
                                        senteobjs         ;; is being set on start
                                        ]
                                 :cljs [recv-fn           ;; fn to call for incoming msg
                                        path              ;; path to server
                                        senteobjs         ;; is being set on start
                                        ])

  p/Component

  (deliver [this msg]
    (do-send this msg))

  (deliver-sync [this msg]
    (throw (ex-info "not implemented" {}))))

;;; TODO: implement client for server side (not via sente, see factum project):
#?(:clj  (defn tunnel [recv-fn userid-fn & {:keys [userid-kw]}]
           (map->ComponentWebsocket {:recv-fn recv-fn
                                     :userid-fn userid-fn
                                     :userid-kw (or userid-kw ::userid)}))
   :cljs (defn tunnel [recv-fn path]
           (map->ComponentWebsocket {:recv-fn recv-fn
                                     :path path})))

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
