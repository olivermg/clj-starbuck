(ns ow.starbuck.message-router
  (:require [taoensso.timbre :refer [trace debug info warn error fatal]]
            #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]
            [ow.starbuck.protocols :as p]
            #_[ow.starbuck.core :refer [defcomponentrecord] :as oc]
            ))

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

(defn- get-component-unit [{:keys [ruleset] :as router} component]
  (-> (:units ruleset)
      owc/map-invert-coll
      (get component)))

(defn- get-tunnel [{:keys [unit] :as router} component]
  (and unit
       (let [cunit (get-component-unit router component)]
         (and (not= unit cunit)
              cunit))))

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

(defn advance [{:keys [ruleset] :as router} msg]
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

(defn make-router [ruleset components & {:keys [unit dispatch?]
                                         :or {dispatch? true}}]
  {:unit unit
   :components components
   :ruleset ruleset
   :dispatch? dispatch?})

#_(defrecord MessageRouter [ruleset]

  p/Router

  (advance [this msg]
    (advance ruleset msg)))

#_(defn message-router [ruleset]
  (->MessageRouter ruleset))

(defn message [route map & {:keys [max-transitions]
                            :or {max-transitions 100}}]
  "Creates a new routable message."
  (assoc map
         ::route route
         ::component '()
         ::transition-count 0
         ::max-transitions max-transitions))

(defn dispatch [{:keys [components] :as router} msg]
  (when-let [component (peek (::component msg))]
    (let [tunnel (get-tunnel router component)
          dest (or tunnel component)
          destcomp (get components dest)]
      (cond (fn? destcomp)                   (destcomp msg)
            (satisfies? ap/Channel destcomp) (a/put! destcomp msg)
            (satisfies? p/Tunnel destcomp)   (p/send destcomp msg)
            true (throw (ex-info "cannot dispatch" {:router router
                                                    :msg msg
                                                    :tunnel tunnel
                                                    :component component
                                                    :dest dest}))))))

#_(defn- process [this req]
  (advance req (:ruleset this)))

#_(defcomponentrecord MessageRouter)

#_(defn new-comp [ch-in ch-out ruleset]
  (map->MessageRouter {:processfn process
                       :ch-in ch-in
                       :ch-out ch-out
                       :ruleset ruleset}))



(comment

  (def ruleset1
    {:units
     {:browser {:components #{:overbooked-checker :booker}
                :via :server-tunnel}
      :server {:components #{:auth-checker}
               :via :server-tunnel}}

     ;;; you can have many routes
     :routes

     ;;; defining a route :book-flight
     {:book-flight

      ;;; routes define transitions from one "component" to other "component(s)"
      {:transitions {nil :auth-checker

                     :auth-checker {:next (fn [msg]
                                            (ow.starbuck.tunnel-simple/->TunnelSimple ruleset2))}

                     :overbooked-checker {:next (fn [msg] :booker)}

                     :booker {:transform (fn [msg] (assoc msg :checked true))
                              :next [:invoice-generator (fn [msg] :notifier)]}}}}})

  #_(def router1 (message-router ruleset1))

  (->> (message :book-flight {:foo :bar})
       (advance ruleset1)
       first
       (advance ruleset1)
       first
       (dispatch))


  (dispatch {:unit :server
             :components {:a #(println "a" %)
                          :b #(println "b" %)
                          :c #(println "c" %)
                          :d #(println "d" %)
                          :e #(println "e" %)
                          :f #(println "f" %)
                          :browser #(println "browser" %)
                          :server #(println "server" %)}
             :ruleset {:units {:browser #{:a :b :c}
                               :server #{:d :e :f}}}}
            {::component (list :c)
             :foo :bar})

  )
