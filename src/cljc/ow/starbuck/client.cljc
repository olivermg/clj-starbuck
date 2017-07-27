(ns ow.starbuck.client
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.starbuck.protocols :as p]))

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



(comment

  (def ruleset1
    {:units {:browser #{:input-validator :input-sanitizer :browser-result}
             :server  #{:auth-checker :booker :invoice-generator :notifier :server-result}}

     :routes {:book-flight
              {:transitions {nil :input-validator

                             :input-validator {:next :input-sanitizer}

                             :input-sanitizer {:next (fn [msg] :auth-checker)}

                             :auth-checker {:transform (fn [msg] (assoc msg :check #{:username :token}))
                                            :next :booker}

                             :booker {:next [:invoice-generator
                                             (fn [msg] :notifier)
                                             :browser-result]}}}}})


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

  (do (require '[ow.starbuck.impl.core-async :as ica])

      (def browser-comp-ch     (a/chan))
      (def browser-comp-ch-pub (ica/component-pub browser-comp-ch))
      (def browser-router-ch   (a/chan))
      (def browser-result-ch   (a/chan))
      (def browser-result-pub  (ica/result-pub browser-result-ch))

      (def server-comp-ch     (a/chan))
      (def server-comp-ch-pub (ica/component-pub server-comp-ch))
      (def server-router-ch   (a/chan))
      (def server-result-ch   (a/chan))
      (def server-result-pub  (ica/result-pub server-result-ch))

      (defn start-async-component [unit comp-kw input-pub output-ch]
        (-> (ica/component (str (name unit) "." (name comp-kw))
                           (ica/component-sub input-pub comp-kw)
                           output-ch
                           (fn [this msg]
                             (Thread/sleep (rand-int 2000))
                             (update msg :visited-components #(conj % comp-kw))))
            (ica/start)))

      (defn start-async-silent-component [unit comp-kw input-pub]
        (-> (ica/silent-component (str (name unit) "." (name comp-kw))
                                  (ica/component-sub input-pub comp-kw)
                                  (fn [this msg]
                                    (Thread/sleep (rand-int 2000))
                                    (update msg :visited-components #(conj % comp-kw))))
            (ica/start)))

      (defn start-async-echo-component [unit comp-kw input-pub output-ch]
        (-> (ica/echo-component (str (name unit) "." (name comp-kw))
                                (ica/component-sub input-pub comp-kw)
                                output-ch)
            (ica/start)))

      (defn start-async-router [config input-ch result-pub]
        (-> (ica/router config input-ch :result-pub result-pub :name (str (name (:unit config)) " router"))
            (ica/start)))

      (defn start-async-tunnel [name output-ch]
        (-> (ica/tunnel output-ch :name name)
            (ica/start)))

      (def browser-components2 {:components {:input-validator (start-async-component :brw :input-validator browser-comp-ch-pub browser-router-ch)
                                             :input-sanitizer (start-async-component :brw :input-sanitizer browser-comp-ch-pub browser-router-ch)
                                             :browser-result  (start-async-echo-component :brw :browser-result browser-comp-ch-pub browser-result-ch)}
                                :tunnels {:server (start-async-tunnel "browser->server" server-router-ch)}})

      (def server-components2 {:components {:auth-checker      (start-async-component :srv :auth-checker server-comp-ch-pub server-router-ch)
                                            :booker            (start-async-component :srv :booker server-comp-ch-pub server-router-ch)
                                            :invoice-generator (start-async-silent-component :srv :invoice-generator server-comp-ch-pub)
                                            :notifier          (start-async-silent-component :srv :notifier server-comp-ch-pub)
                                            :server-result     (start-async-echo-component :srv :server-result server-comp-ch-pub server-result-ch)}
                               :tunnels {:browser (start-async-tunnel "server->browser" browser-router-ch)}})

      (def browser-config2 (config ruleset1 browser-components2 :unit :browser))
      (def server-config2 (config ruleset1 server-components2 :unit :server))

      (def browser-router (start-async-router browser-config2 browser-router-ch browser-result-pub))
      (def server-router  (start-async-router server-config2 server-router-ch server-result-pub)))

  (do (->> (message :book-flight {:foo :bar1 :sync false} :max-transitions 20)
           (p/deliver browser-router))

      (->> (message :book-flight {:foo :bar2 :sync false} :max-transitions 20)
           (p/deliver browser-router))

      (->> (message :book-flight {:foo :baz1 :sync true} :max-transitions 20)
           (p/deliver-sync browser-router))
      )

  )
