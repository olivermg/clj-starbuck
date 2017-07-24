(ns ow.starbuck.client
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go go-loop] :as a]
               :cljs [cljs.core.async :as a])))

(defn config [ruleset components & {:keys [unit]}]
  {:unit unit
   :components components
   :ruleset ruleset})

(defn message [route map & {:keys [max-transitions]
                            :or {max-transitions 100}}]
  "Creates a new routable message."
  (assoc map
         :ow.starbuck.routing/route route
         :ow.starbuck.routing/component '()
         :ow.starbuck.routing/transition-count 0
         :ow.starbuck.routing/max-transitions max-transitions))

(defn process-message [router-ch msg]
  (a/put! router-ch msg))

(defn process-message-sync [router-ch components-pub listen-kw response-fn msg & {:keys [timeout]}]
  (let [req-id (rand-int Integer/MAX_VALUE)
        msg (assoc msg ::request-id req-id)
        subch (a/chan)
        ch (-> components-pub
               (a/sub listen-kw subch)
               (a/pub ::request-id)
               (a/sub req-id (a/promise-chan)))]
    (go (let [[res _] (a/alts! [ch (a/timeout (or timeout 10000))])]
          (a/close! subch)
          (when-not (nil? res)
            (response-fn res))))))



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

  (do (require '[ow.starbuck.components.core-async :as ca])
      (require '[ow.starbuck.routers.core-async :as ra])
      (require '[ow.starbuck.tunnels.core-async :as ta])

      (def browser-comp-ch     (a/chan))
      (def browser-comp-ch-pub (a/pub browser-comp-ch #(peek (:ow.starbuck.routing/component %))))
      (def browser-router-ch   (a/chan))

      (def server-comp-ch     (a/chan))
      (def server-comp-ch-pub (a/pub server-comp-ch #(peek (:ow.starbuck.routing/component %))))
      (def server-router-ch   (a/chan))

      (defn start-async-component [comp-kw input-pub output-ch]
        (-> (ca/component comp-kw input-pub output-ch
                          (fn [msg]
                            (println "got component msg in" comp-kw "-" msg
                                     "on thread" (.getId (Thread/currentThread)))
                            (update msg :visited-components #(conj % comp-kw))))
            (ca/start)))

      (defn start-async-router [config input-ch]
        (-> (ra/router config input-ch)
            (ra/start)))

      (def browser-components2 {:components {:input-validator (start-async-component :input-validator browser-comp-ch-pub browser-router-ch)
                                             :input-sanitizer (start-async-component :input-sanitizer browser-comp-ch-pub browser-router-ch)}
                                :tunnels {:server (ta/tunnel server-router-ch)}})

      (def server-components2 {:components {:auth-checker (start-async-component :auth-checker server-comp-ch-pub server-router-ch)
                                            :booker (start-async-component :booker server-comp-ch-pub server-router-ch)
                                            :invoice-generator (start-async-component :invoice-generator server-comp-ch-pub server-router-ch)
                                            :notifier (start-async-component :invoice-generator server-comp-ch-pub server-router-ch)
                                            ;;;:DEFAULT server-comp-ch
                                            }
                               :tunnels {:browser (ta/tunnel browser-router-ch)}})

      (def browser-config2 (config ruleset1 browser-components2 :unit :browser))
      (def server-config2 (config ruleset1 server-components2 :unit :server)))

  (do (doseq [c #{:input-validator :input-sanitizer}]
        (start-async-component c browser-comp-ch-pub browser-router-ch))

      (doseq [c #{:auth-checker :booker :invoice-generator :notifier}]
        (start-async-component c server-comp-ch-pub server-router-ch))

      (start-async-router browser-config2 browser-router-ch)
      (start-async-router server-config2 server-router-ch))

  (->> (message :book-flight {:foo :bar})
       #_(route browser-config2)    ;; you can start like this
       (a/put! browser-router-ch) ;; or this
       )

  )
