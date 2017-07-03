(ns ow.starbuck.message-router
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            [ow.starbuck.core :refer [defcomponentrecord] :as oc]))

(defn- printable-msg [msg]
  (select-keys msg #{::route ::component ::transition-count ::max-transitions}))

(defn- inc-transition-count [msg]
  (update msg ::transition-count
          #(inc (or % 0))))

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

(defn- transform [transition msgs]
  (if-let [f (get transition :transform)]
    (map f msgs)
    msgs))

(defn- goto-next [transition msgs]
  (if-let [nextfs (next->fns transition)]
    (->> (map #(map (fn [nextf]
                      (assoc % ::component (nextf %)))
                    nextfs)
              msgs)
         (apply concat)
         (remove #(nil? (::component %))))
    (do (warn "next component undefined in transition" {:transition transition
                                                        :msgs (mapv printable-msg msgs)})
        [])))

(defn- goto-next-and-transform [transitions msgs]
  (->> (map #(->> (goto-next % msgs)
                  (transform %))
            transitions)
       (apply concat)))

(defn- handle-events [eventhandlers msg]
  (reduce (fn [s [key eventhandler]]
            (if-let [nextfs (next->fns eventhandler)]
              (if (contains? msg key)
                (let [evmsgs (map #(assoc msg ::component (% msg))
                                  nextfs)]
                  (-> (update s :abort? #(or % (:abort? eventhandler)))
                      (update :evmsgs #(concat % evmsgs))))
                s)
              (do (warn "next component undefined in eventhandler" {:eventhandler eventhandler
                                                                    :msg (printable-msg msg)})
                  s)))
          {:abort? nil
           :evmsgs []}
          eventhandlers))

(defn message [route map & {:keys [max-transitions]
                            :or {max-transitions 100}}]
  "Creates a new routable message."
  (assoc map
         ::route route
         ::component nil
         ::transition-count 0
         ::max-transitions max-transitions))

(defn advance [ruleset msg]
  "Takes a message, runs it against ruleset and returns a sequence of routed messages."
  (debug "routing starting with" (printable-msg msg))
  (if (< (::transition-count msg) 100)
    (let [route (get-in ruleset [:routes (::route msg)])
          trans (get-in route [:transitions (::component msg)])
          transs (if (sequential? trans)
                   trans
                   [trans])
          evenths (merge (get ruleset :event-handlers)
                         (get route :event-handlers))
          {:keys [evmsgs abort?]} (handle-events evenths msg)
          resmsgs (if abort?
                    evmsgs
                    (concat evmsgs (->> (goto-next-and-transform transs [msg])
                                        (map inc-transition-count))))]
      (debug "routing ending with" (mapv printable-msg resmsgs))
      resmsgs)
    (do (warn "dropping message due to high transition-count")
        [])))



(defn- process [this req]
  (advance req (:ruleset this)))

(defcomponentrecord MessageRouter)

(defn new-comp [ch-in ch-out ruleset]
  (map->MessageRouter {:processfn process
                       :ch-in ch-in
                       :ch-out ch-out
                       :ruleset ruleset}))



(comment

  (def ruleset1
    ;;; you can have many routes
    {:routes

     ;;; defining a route :book-flight
     {:book-flight

      ;;; routes define transitions from one "component" to other "component(s)"
      {:transitions {nil :auth-checker

                     :auth-checker {:next :overbooked-checker}

                     :overbooked-checker {:next (fn [msg] :booker)}

                     :booker {:transform (fn [msg] (assoc msg :checked true))
                              :next [:invoice-generator (fn [msg] :notifier)]}}}}})

  )
