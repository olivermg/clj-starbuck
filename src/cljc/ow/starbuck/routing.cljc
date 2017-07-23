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


(defn message [route map & {:keys [max-transitions]
                            :or {max-transitions 100}}]
  "Creates a new routable message."
  (assoc map
         ::route route
         ::component '()
         ::transition-count 0
         ::max-transitions max-transitions))

(defn make-config [ruleset components & {:keys [unit]}]
  {:unit unit
   :components components
   :ruleset ruleset})



(comment

  (def ruleset1
    {:units {:browser #{:input-validator :input-sanitizer}
             :server #{:auth-checker :booker :invoice-generator :notifier}}

     :routes {:book-flight
              {:transitions {nil :input-validator

                             :input-validator {:next :input-sanitizer}

                             :input-sanitizer {:next (fn [msg] :auth-checker)}

                             :auth-checker {:transform (fn [msg] (assoc msg :check #{:username :token}))
                                            :next :booker}

                             :booker {:next [:invoice-generator (fn [msg] :notifier)]}}}}})


  ;;; via fns

  (do (def browser-components1 {:components {:input-validator #(doto % (println "(input-validator)"))
                                             :input-sanitizer #(doto % (println "(input-sanitizer)"))}
                                :tunnels {:server #(doto % (println "(browser -> server)"))}})

      (def server-components1 {:components {:auth-checker #(doto % (println "(auth-checker)"))
                                            :booker #(doto % (println "(booker)"))
                                            :invoice-generator #(doto % (println "(invoice-generator)"))
                                        ;;;:notifier #(doto % (println "(notifier)"))
                                            :DEFAULT #(doto % (println "(DEFAULT)"))}
                               :tunnels {:browser #(doto % (println "(server -> browser)"))}})

      (def browser-config1 (make-config ruleset1 browser-components1 :unit :browser))
      (def server-config1 (make-config ruleset1 server-components1 :unit :server)))

  (->> (message :book-flight {:foo :bar})
       (route browser-config1)
       first
       (route browser-config1)
       first
       (route browser-config1)
       first
       (route server-config1)
       first
       (route server-config1)
       first
       (route server-config1))


  ;;; via core.async

  (do (def browser-comp-ch     (a/chan))
      (def browser-comp-ch-pub (a/pub browser-comp-ch #(peek (:ow.starbuck.routing/component %))))
      (def browser-router-ch   (a/chan))

      (def server-comp-ch     (a/chan))
      (def server-comp-ch-pub (a/pub server-comp-ch #(peek (:ow.starbuck.routing/component %))))
      (def server-router-ch   (a/chan))

      (def browser-components2 {:components {:input-validator browser-comp-ch
                                             :input-sanitizer browser-comp-ch}
                                :tunnels {:server #(do (println % "(browser -> server)")
                                                       (a/put! server-router-ch %))}})

      (def server-components2 {:components {:auth-checker server-comp-ch
                                            :booker server-comp-ch
                                            :invoice-generator server-comp-ch
                                            ;;;:notifier server-comp-ch
                                            :DEFAULT server-comp-ch}
                               :tunnels {:browser #(do (println % "(server -> browser)")
                                                       (a/put! browser-router-ch %))}})

      (def browser-config2 (make-config ruleset1 browser-components2 :unit :browser))
      (def server-config2 (make-config ruleset1 server-components2 :unit :server))

      (defn start-async-component [comp-kw input-pub output-ch]
        (let [sub-ch (a/sub input-pub comp-kw (a/chan))
              timeout (+ 20000 (rand-int 2000))]
          (go-loop [[msg _] (a/alts! [sub-ch (a/timeout timeout)])]
            (println "got component msg in" comp-kw "-" msg "on thread" (-> Thread .currentThread .getId))
            (when msg
              (Thread/sleep 1000)
              (a/put! output-ch (update msg :visited-components #(conj % [comp-kw (java.util.Date.)])))
              (recur (a/alts! [sub-ch (a/timeout timeout)]))))))

      (defn start-async-router [config router-kw input-ch output-ch]
        (let [timeout (+ 20000 (rand-int 2000))]
          (go-loop [[msg _] (a/alts! [input-ch (a/timeout timeout)])]
            (println "got router msg in" router-kw "-" msg "on thread" (-> Thread .currentThread .getId))
            (when msg
              (Thread/sleep 1000)
              (route config msg)
              (recur (a/alts! [input-ch (a/timeout timeout)])))))))

  (do (doseq [c #{:input-validator :input-sanitizer}]
        (start-async-component c browser-comp-ch-pub browser-router-ch))

      (doseq [c #{:auth-checker :booker :invoice-generator :notifier}]
        (start-async-component c server-comp-ch-pub server-router-ch))

      (start-async-router browser-config2 :browser browser-router-ch browser-comp-ch)
      (start-async-router server-config2 :server server-router-ch server-comp-ch))

  (->> (message :book-flight {:foo :bar})
       #_(route browser-config2)    ;; you can start like this
       (a/put! browser-router-ch) ;; or this
       )

  )
