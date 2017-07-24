(ns ow.starbuck.client
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]))

(defn message [route map & {:keys [max-transitions]
                            :or {max-transitions 100}}]
  "Creates a new routable message."
  (assoc map
         ::route route
         ::component '()
         ::transition-count 0
         ::max-transitions max-transitions))

(defn config [ruleset components & {:keys [unit]}]
  {:unit unit
   :components components
   :ruleset ruleset})

(defn trigger-route [router-ch msg]
  (a/put! router-ch msg))

(defn trigger-route-sync [router-ch component-pub listen-kw msg]
  )



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
