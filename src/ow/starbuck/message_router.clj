;;;
;;; Copyright 2016 Oliver Wegner
;;;
;;; Licensed under the Apache License, Version 2.0 (the "License");
;;; you may not use this file except in compliance with the License.
;;; You may obtain a copy of the License at
;;;
;;; http://www.apache.org/licenses/LICENSE-2.0
;;;
;;; Unless required by applicable law or agreed to in writing, software
;;; distributed under the License is distributed on an "AS IS" BASIS,
;;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;; See the License for the specific language governing permissions and
;;; limitations under the License.
;;;

(ns ow.starbuck.message-router
  (:require [com.stuartsierra.component :as c]
            [clojure.core.async :refer [put! >! >!! <! <!! go go-loop pub sub chan close! timeout alts!! alts! promise-chan]]
            [taoensso.timbre :refer [trace debug info warn error fatal]]
            [clara.rules :refer :all]

            [clojure.walk :refer [macroexpand-all]]
            [clojure.string :as str]
            [ow.starbuck.core :refer [defcomponentrecord] :as oc]
            ))

;;;
;;; REQUIREMENTS:
;;;  - routes that transition from one component to the next.
;;;  - aborting routes, e.g. when an :error occurs.
;;;  - route-local events, e.g. a mail should be sent when :pending-mails is set.
;;;  - global events.
;;;  - dependencies among transitions and events, e.g. one component might set :pending-mails,
;;;    but before :mailer picks that one up, it needs to get augmented with more info.
;;;  - must support branching & merging
;;;
;;;  - parallelize some transitions, e.g. augmenting different entities.
;;;  - define some config during routing definition. that'll allow to have e.g. only one
;;;    augmentation component that'll augment the correct entity, depending on the route it's
;;;    being used in.
;;;

(defonce ^:private ^:dynamic *route* nil)

(defrecord PendingMessage [route comp])
(defrecord RoutedMessage [route comp])
(defrecord Abort [route])

(defn- concat-symbols [prefix & syms]
  (letfn [(sym-to-str [s]
            (cond
              (nil? s) "nil"
              (fn? s) (str s)
              (sequential? s) (str/join "-or-" (map sym-to-str s))
              true (name s)))]
    (symbol (str (name prefix) "-"
                 (str/replace
                  (str/join "-" (map sym-to-str syms))
                  #"[\.\/]" "_")))))

(defmacro defroutes [& body]
  `(do ~@body

       (defquery ~'get-routed-messages []
         [~'?req ~'<- RoutedMessage])))

(defmacro within-route [route & body]
  (binding [*route* route]
    (macroexpand-all `(do ~@body))))

(defmacro transition [prev-comps next-comp]
  {:pre [(not (nil? *route*))]}
  (let [next-comp (eval next-comp)
        rname (concat-symbols "transition" *route* prev-comps next-comp)
        logmsg (str "Transition " prev-comps " -> " next-comp
                    " (route " *route* ")")
        reqsym (gensym "?req-")
        prev-comps (set (if (sequential? prev-comps)
                          prev-comps
                          [prev-comps]))
        ]
    `(defrule ~rname
       [~reqsym ~'<- PendingMessage (= ~*route* ~'route) (contains? ~prev-comps ~'comp)]
       [:not [Abort (or (nil? ~'route) (= ~*route* ~'route))]]
       ~'=>
       (debug ~logmsg)
       (insert! (map->RoutedMessage (assoc ~reqsym :comp ~(if (fn? next-comp)
                                                            `(~next-comp ~reqsym)
                                                            next-comp)))))))

(defmacro on [key next-comp & {:keys [abort?]}]
  (let [rname (concat-symbols "on" *route* key)
        logmsg (str "On " key " -> " next-comp
                    " (route " *route* ", abort? " abort? ")")
        reqsym (gensym "?req-")
        keysym (symbol (name key))]
    `(defrule ~rname
       [~reqsym ~'<- PendingMessage [{:keys [~'route ~keysym]}]
        ~@(when *route* `[(= ~*route* ~'route)]) (not (nil? ~keysym))]
       ~@(when (not abort?)
           `[[:not ~(if *route*
                      `[Abort (or (nil? ~'route) (= ~*route* ~'route))]
                      `[Abort (nil? ~'route)])]])
       ~'=>
       (debug ~logmsg)
       ~@(when abort?
           `[(insert! (->Abort ~*route*))])
       (insert! (map->RoutedMessage (assoc ~reqsym :comp ~next-comp))))))

(defmacro postprocess [prev-comps f]
  {:pre [(not (nil? *route*))]}
  (let [rname (concat-symbols "postprocess" *route* prev-comps)
        logmsg (str "Postprocess after " prev-comps " (route " *route* ")")
        reqsym (gensym "?req-")
        prev-comps (set (if (sequential? prev-comps)
                          prev-comps
                          [prev-comps]))]
    `(defrule ~rname
       [~reqsym ~'<- PendingMessage [{~'postprocessed? ::postprocessed?
                                      ~'route :route
                                      ~'comp :comp}]
        (= ~*route* ~'route) (contains? ~prev-comps ~'comp) (not ~'postprocessed?)]
       [:not [Abort (or (nil? ~'route) (= ~*route* ~'route))]]
       ~'=>
       (debug ~logmsg)
       (insert! (map->PendingMessage (assoc (~f ~reqsym) ::postprocessed? true))))))

(defn process [this req]
  (let [rns (:rulens this)]
    (map #(into {} (-> % :?req (dissoc ::postprocessed?))) ;; into is necessary here, as records don't implement IFn
         (-> (mk-session rns)
             (insert (map->PendingMessage req))
             (fire-rules)
             (query @(ns-resolve rns 'get-routed-messages))
             ))))

(defcomponentrecord MessageRouter)

(defn new-comp [ch-in ch-out rulens]
  (map->MessageRouter {:processfn process
                       :ch-in ch-in
                       :ch-out ch-out
                       :rulens rulens}))
