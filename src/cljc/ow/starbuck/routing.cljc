(ns ow.starbuck.routing
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            [ow.clojure :as owc]
            [ow.starbuck.protocols :as p]))

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
                      (update % ::component
                              (fn [prev-comps]
                                (apply list (take 2 (cons (nextf %) prev-comps))))))
                    nextfs)
              msgs)
         (apply concat)
         (remove #(nil? (peek (::component %)))))
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
                (let [evmsgs (map #(update msg ::component
                                           (fn [prev-comps]
                                             (apply list (take 2 (cons (% msg) prev-comps)))))
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

(defn advance [{:keys [ruleset] :as config} msg]
  "Takes a message, runs it against ruleset and returns a sequence of routed messages."
  (debug "routing starting with" (printable-msg msg))
  (if (< (::transition-count msg) 100)
    (let [route (get-in ruleset [:routes (::route msg)])
          trans (get-in route [:transitions (peek (::component msg))])
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

(defn- get-component-unit [{:keys [ruleset] :as config} component]
  (-> (:units ruleset)
      owc/map-invert-coll
      (get component)))

(defn- get-tunnel [{:keys [unit] :as config} component]
  (and unit
       (let [cunit (get-component-unit config component)]
         (and (not= unit cunit)
              cunit))))

(defn- dispatch-tunnel [{:keys [components] :as config} tunnel msg]
  (let [tunnel-comp ^p/Tunnel (get-in components [:tunnels tunnel])
        msg (update msg ::component pop)]
    (p/send tunnel-comp msg)))

(defn- dispatch-component [{:keys [components] :as config} component msg]
  (let [comp ^p/Component (or (get-in components [:components component])
                              (get-in components [:components :DEFAULT]))]
    (p/process comp msg)))

(defn dispatch [config msg]
  (when-let [component (peek (::component msg))]
    (if-let [tunnel (get-tunnel config component)]
      (dispatch-tunnel config tunnel msg)
      (dispatch-component config component msg))))

#_(defn route [config msg]
  (doall (map (partial dispatch config)
              (advance config msg))))

#_(defn route [config msg]
  (try
    (route* config msg)
    (catch Exception e
      (println "EXCEPTION (ROUTER):" e))
    (catch Error e
      (println "ERROR (ROUTER):" e))))
