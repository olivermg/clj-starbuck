(ns ow.starbuck.message-router
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            ;;;[clara.rules :as cr]

            ;;;[clojure.walk :refer [macroexpand-all]]
            ;;;[clojure.string :as str]
            [ow.starbuck.core :refer [defcomponentrecord] :as oc]
            ))

;;;
;;; EXAMPLE RULESET:
;;;
(def example-ruleset
  {:routes {:switch-booking

            {:transitions {nil {:next :database-reader}

                           :database-reader {:next (fn [req] :converter)
                                             :postprocess (fn [req] (assoc req :postprocessed true))}

                           :converter {:preprocess (fn [req] (assoc req :preprocessed true))
                                       :next [:logger :http-server]}

                           :logger {:next (fn [req] (println "HANDLING LOG REQ") nil)}}

             :event-handlers {:error {:next :http-server
                                      :abort? true}}}}

   :event-handlers {:pending-mails {:next :mailer}}})

#_(defn compile [cfg]
  "Takes a config and compiles it into a ruleset."
  (let [ns (create-ns (gensym "message-router-ruleset-"))]
    ))

(defn- doprocess [type transition msgs]
  (if-let [f (get transition type)]
    (map f msgs)
    msgs))

(def preprocess (partial doprocess :preprocess))
(def postprocess (partial doprocess :postprocess))

(defn- goto-next [transition msgs]
  (if-let [next (or (and (map? transition) (:next transition))
                    transition)]
    (let [nexts (if (sequential? next)
                  next
                  [next])
          nextfs (map #(if (fn? %)
                         (fn [msg] (% msg))
                         (constantly %))
                      nexts)]
      (->> (map #(map (fn [nextf]
                        (assoc % :comp (nextf %)))
                      nextfs)
                msgs)
           (apply concat)
           (remove #(nil? (:comp %)))))
    (warn "next component undefined in transition" {:transition transition})))

(defn- handle-events [eventhandlers msg]
  (let [{:keys [msg evmsgs]}
        (reduce (fn [s [key eventhandler]]
                  (if-let [next (:next eventhandler)]
                    (if (contains? msg key)
                      (let [evmsg (assoc msg :comp next)]
                        (if (:abort? eventhandler)
                          (-> (dissoc s :msg)
                              (update :evmsgs #(conj % evmsg)))
                          (update s :evmsgs #(conj % evmsg))))
                      s)
                    (warn "next component undefined in eventhandler" {:eventhandler eventhandler})))
                {:msg msg
                 :evmsgs []}
                eventhandlers)]
    (if msg
      (conj evmsgs msg)
      evmsgs)))

(defn- handle-events-multi [eventhandlers msgs]
  (->> (map #(handle-events eventhandlers %) msgs)
       (apply concat)))

(defn advance [msg ruleset]
  "Takes a message, runs it against ruleset and returns a sequence of routed messages."
  (debug "routing starting with" (select-keys msg #{:route :comp :starbuck/route-count}))
  (if (< (or (:starbuck/route-count msg) 0) 100)
    (let [route (get-in ruleset [:routes (:route msg)])
          trans (get-in route [:transitions (:comp msg)])
          evenths (merge (get ruleset :event-handlers)
                         (get route :event-handlers))
          f (comp (partial handle-events-multi evenths)
                  (partial postprocess trans)
                  (partial goto-next trans)
                  (partial preprocess trans))
          res (->> (f [msg])
                   (map #(update % :starbuck/route-count (fn [n] (inc (or n 0))))))]
      (debug "routing ending with" (mapv #(select-keys % #{:route :comp :starbuck/route-count}) res))
      res)
    (do (warn "dropping message due to high route-count")
        [])))



(defn- process [this req]
  (advance req (:ruleset this)))

(defcomponentrecord MessageRouter)

(defn new-comp [ch-in ch-out ruleset]
  (map->MessageRouter {:processfn process
                       :ch-in ch-in
                       :ch-out ch-out
                       :ruleset ruleset}))
