(ns ow.starbuck.message-router
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            [ow.starbuck.core :refer [defcomponentrecord] :as oc]))

(defn- printable-msg [msg]
  (select-keys msg #{:route :comp :starbuck/route-count}))

(defn- doprocess [type transition msgs]
  (if-let [f (get transition type)]
    (map f msgs)
    msgs))

(def preprocess (partial doprocess :preprocess))
(def postprocess (partial doprocess :postprocess))

(defn- next->fns [m]
  (when-let [next (or (and (map? m) (:next m))
                      m)]
    (let [nexts (if (sequential? next)
                  next
                  [next])]
      (map #(if (fn? %)
              (fn [msg] (% msg))
              (constantly %))
           nexts))))

(defn- goto-next [transition msgs]
  (if-let [nextfs (next->fns transition)]
    (->> (map #(map (fn [nextf]
                      (assoc % :comp (nextf %)))
                    nextfs)
              msgs)
         (apply concat)
         (remove #(nil? (:comp %))))
    (do (warn "next component undefined in transition" {:transition transition
                                                        :msgs (mapv printable-msg msgs)})
        [])))

(defn- handle-events [eventhandlers msg]
  (let [{:keys [abort? evmsgs]}
        (reduce (fn [s [key eventhandler]]
                  (if-let [nextfs (next->fns eventhandler)]
                    (if (contains? msg key)
                      (let [evmsgs (map #(assoc msg :comp (% msg))
                                        nextfs)]
                        (-> (update s :abort? #(or % (:abort? eventhandler)))
                            (update :evmsgs #(concat % evmsgs))))
                      s)
                    (do (warn "next component undefined in eventhandler" {:eventhandler eventhandler
                                                                          :msg (printable-msg msg)})
                        s)))
                {:abort? nil
                 :evmsgs []}
                eventhandlers)]
    (if abort?
      evmsgs
      (conj evmsgs msg))))

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
