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

(ns ow.starbuck.core
  (:require [com.stuartsierra.component :as c]
            [clojure.core.async :refer [put! >! >!! <! <!! go go-loop pub sub chan close! timeout alts!! promise-chan]]
            [taoensso.timbre :refer [trace debug info warn error fatal]]))

(defprotocol MessageRouting
  (dispatch [this req]))

(defn safe-process [this f req & args]
  (try
    (apply f this req args)
    (catch Exception e
      (warn "EXCEPTION in component:" (pr-str e))
      (merge req {:error e}))
    (catch Error e
      (warn "ERROR in component:" (pr-str e))
      (merge req {:error e}))))

(defmacro defcomponentrecord [name & {:keys [silent? startfn stopfn]}]
  "Defines a component."
  (let [stopkw (keyword (str *ns*) "stop")
        this1 (gensym "this-")
        this2 (gensym "this-")
        req (gensym "req-")]
    `(defrecord ~name [~'processfn ~'ch-in ~'ch-out     ;; constructor-initialized fields
                       ]                                ;; start/stop-initialized fields

       MessageRouting

       (dispatch [this# req#]
         (debug "routing message...")
         (put! ~'ch-out req#))

       c/Lifecycle

       (start [~this1]
         (info "starting...")
         (go-loop [~req (<! ~'ch-in)]
           (if-not (= ~req ~stopkw)
             (do (future
                   (debug "got request")
                   ~(if silent?
                      `(do (debug "silent component - will ignore response(s)")
                           (safe-process ~this1 ~'processfn ~req))
                      `(let [res# (safe-process ~this1 ~'processfn ~req)]
                         (debug "sending response(s)")
                         (cond
                           (nil? res#)        nil
                           (sequential? res#) (dorun (map #(dispatch ~this1 %) res#))
                           true               (dispatch ~this1 res#)))))
                 (recur (<! ~'ch-in)))
             (info "stopped.")))
         (let [~this1 ~(if startfn
                         `(~startfn ~this1)
                         this1)]
           (info "started.")
           ~this1))

       (stop [~this2]
         (info "stopping...")
         (let [~this2 ~(if stopfn
                         `(~stopfn ~this2)
                         this2)]
           (put! ~'ch-in ~stopkw)
           (info "stopped.")
           ~this2)))))
