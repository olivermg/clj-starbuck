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

(defn safe-process [this f req & args]
  (try
    (apply f this req args)
    (catch Exception e
      (warn "EXCEPTION in component:" (pr-str e))
      (merge req {:error e}))
    (catch Error e
      (warn "ERROR in component:" (pr-str e))
      (merge req {:error e}))))

(defmacro defcomponentrecord [name]
  (let [stopkw (keyword (str *ns*) "stop")]
    `(defrecord ~name [~'processfn ~'ch-in ~'ch-out     ;; constructor-initialized fields
                       ]                                ;; start/stop-initialized fields

       c/Lifecycle

       (start [this#]
         (info "starting...")
         (go-loop [req# (<! ~'ch-in)]
           (if-not (= req# ~stopkw)
             (do (debug "got request")
                 (let [res# (safe-process this# ~'processfn req#)]
                   (debug "sending response(s)")
                   (cond
                     (nil? res#)        nil
                     (sequential? res#) (dorun (map #(put! ~'ch-out %) res#))
                     true               (put! ~'ch-out res#)))
                 (recur (<! ~'ch-in)))
             (info "stopped.")))
         (info "started.")
         this#)

       (stop [this#]
         (info "stopping...")
         (put! ~'ch-in ~stopkw)
         (info "stopped.")
         this#))))
