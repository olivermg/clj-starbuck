(ns ow.starbuck.message-router
  (:refer-clojure :rename {compile compile-clj})
  #_#?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require #_[com.stuartsierra.component :as c]
            #_#?(:clj  [clojure.core.async :refer [put! >! <! go go-loop pub sub chan close! timeout alts! promise-chan]]
               :cljs [cljs.core.async :refer [put! >! <! pub sub chan close! timeout alts! promise-chan]])
            [taoensso.timbre :refer [trace debug info warn error fatal]]
            [clara.rules :as cr]

            ;;;[clojure.walk :refer [macroexpand-all]]
            [clojure.string :as str]
            ;;;[ow.starbuck.core :refer [defcomponentrecord] :as oc]
            ))

;;;
;;; EXAMPLE CONFIG:
;;;
(comment {:routes [{:id :switch-booking

                    :transitions {nil {:next :database-reader}

                                  :database-reader {:next (fn [req] :augmenter)
                                                    :postprocess (fn [req] (assoc req :postprocessed true))}

                                  :converter {:preprocess (fn [req] (assoc req :preprocessed true))
                                              :next [:logger :http-server]}

                                  :logger {:handle (fn [req] (println "LOG REQ"))}}

                    :event-handlers {:error {:next :http-server
                                             :abort? true}}}]

          :event-handlers {:pending-mails {:next :mailer}}})

(defn compile [cfg]
  "Takes a config and compiles it into a ruleset."
  (let [ns (create-ns (gensym "message-router-ruleset-"))]
    ))

(defn advance [ruleset msg]
  "Takes a message, runs it against ruleset and returns a sequence of routed messages."
  )
