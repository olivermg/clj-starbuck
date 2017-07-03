(ns ow.starbuck.message-router-test
  (:require [ow.starbuck.message-router :as mr]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])))

;;;
;;; EXAMPLE RULESET:
;;;

(def example-ruleset
  {:routes {:switch-booking

            {:transitions {;;; you can define the next component statically:
                           ;;;
                           ;;; so in this case, a message without any previous component will be
                           ;;; routed to the :database-reader component.
                           nil :database-reader

                           ;;; you can provide a map instead of a keyword.
                           ;;;
                           ;;; :transform is an optional function that can alter the msg before routing.
                           ;;; :next defines the next component the resulting message will be routed to
                           ;;; after it has been processed by the current component.
                           ;;;
                           ;;; in this case, the message will be processed by the :database-reader
                           ;;; component, then it will be transformed via the function provided in the
                           ;;; :transform keyword and then it will be routed to the :converter component.
                           :database-reader {:transform (fn [msg] (assoc msg :transformed true))
                                             :next :converter}

                           :converter [;;; :next can be either a keyword
                                       {:transform (fn [msg] (assoc msg :transformed 1))
                                        :next :foo}
                                       ;;; ...or a function returning a keyword
                                       {:transform (fn [msg] (assoc msg :transformed 2))
                                        :next (fn [msg] :bar)}
                                       ;;; ...or a sequence of those.
                                       ;;; if it is a sequence, then the message will be "multiplied".
                                       ;;; the following will generate not one, but two resulting messages.
                                       ;;; one going to the :next component :logger and the other one
                                       ;;; going to :http-server
                                       {:transform (fn [msg] (assoc msg :transformed 3))
                                        :next [:logger (fn [msg] :http-server)]}]

                           ;;; if :next is set to nil, the message will not be routed:
                           :logger (fn [msg] nil)
                           :http-server nil}

             ;;; event-handlers generate additional messages, depending on whether or not a msg
             ;;; contains a specified keyword (:error in the example below).
             ;;; you can define event-handlers that are local to the route. local event-handlers
             ;;; override global event-handlers for the same keyword.
             ;;; you can provide a map.
             ;;; :next within that map defines the component the generated msg will be routed to.
             ;;; :abort? (if set to true) will throw away the original message. only the generated
             ;;;         event-handler message will be routed.
             :event-handlers {:error {:next :http-server
                                      :abort? true}}}}

   ;;; these are global event-handlers:
   :event-handlers {:error :foobar
                    :notify (fn [msg] :notifier)
                    :pending-mails [:logger (fn [msg] :mailer)]}})

(t/deftest route1
  (let [msg (mr/message :switch-booking {})]
    (t/is (= (mr/advance example-ruleset msg)
             [{:ow.starbuck.message-router/route            :switch-booking
               :ow.starbuck.message-router/component        :database-reader
               :ow.starbuck.message-router/transition-count 1
               :ow.starbuck.message-router/max-transitions  100}]))))

(t/deftest route2
  (let [msg (merge (mr/message :switch-booking {})
                   {:ow.starbuck.message-router/component        :database-reader
                    :ow.starbuck.message-router/transition-count 1})]
    (t/is (= (mr/advance example-ruleset msg)
             [{:ow.starbuck.message-router/route            :switch-booking
               :ow.starbuck.message-router/component        :converter
               :ow.starbuck.message-router/transition-count 2
               :ow.starbuck.message-router/max-transitions  100
               :transformed true}]))))

(t/deftest route3
  (let [msg (merge (mr/message :switch-booking {})
                   {:ow.starbuck.message-router/component        :converter
                    :ow.starbuck.message-router/transition-count 2})]
    (t/is (= (mr/advance example-ruleset msg)
             [{:ow.starbuck.message-router/route            :switch-booking
               :ow.starbuck.message-router/component        :foo
               :ow.starbuck.message-router/transition-count 3
               :ow.starbuck.message-router/max-transitions  100
               :transformed 1}
              {:ow.starbuck.message-router/route            :switch-booking
               :ow.starbuck.message-router/component        :bar
               :ow.starbuck.message-router/transition-count 3
               :ow.starbuck.message-router/max-transitions  100
               :transformed 2}
              {:ow.starbuck.message-router/route            :switch-booking
               :ow.starbuck.message-router/component        :logger
               :ow.starbuck.message-router/transition-count 3
               :ow.starbuck.message-router/max-transitions  100
               :transformed 3}
              {:ow.starbuck.message-router/route            :switch-booking
               :ow.starbuck.message-router/component        :http-server
               :ow.starbuck.message-router/transition-count 3
               :ow.starbuck.message-router/max-transitions  100
               :transformed 3}]))))

(t/deftest route4
  (let [msg {:route :switch-booking
             :comp :logger}]
    (t/is (= (mr/advance example-ruleset msg)
             []))))

(t/deftest route5
  (let [msg {:route :switch-booking
             :comp :http-server}]
    (t/is (= (mr/advance example-ruleset msg)
             []))))

(t/deftest route6
  (let [msg {:route :switch-booking
             :comp :soaihgfodser}]
    (t/is (= (mr/advance example-ruleset msg)
             []))))

(t/deftest event1
  (let [msg {:route :switch-booking
             :notify true}]
    (t/is (= (mr/advance example-ruleset msg)
             [{:route :switch-booking
               :comp :notifier
               :notify true}
              {:route :switch-booking
               :comp :database-reader
               :starbuck/route-count 1
               :notify true}]))))

(t/deftest event2
  (let [msg {:route :switch-booking
             :comp :database-reader
             :pending-mails true}]
    (t/is (= (mr/advance example-ruleset msg)
             [{:route :switch-booking
               :comp :logger
               :pending-mails true}
              {:route :switch-booking
               :comp :mailer
               :pending-mails true}
              {:route :switch-booking
               :comp :converter
               :starbuck/route-count 1
               :transformed true
               :pending-mails true}]))))

(t/deftest event3
  (let [msg {:route :switch-booking
             :comp :database-reader
             :error true}]
    (t/is (= (mr/advance example-ruleset msg)
             [{:route :switch-booking
               :comp :http-server
               :error true}]))))

(t/deftest route-err1
  (let [msg {:route :98hosedgosg}]
    (t/is (= (mr/advance example-ruleset msg)
             []))))

(t/deftest route-err2
  (let [msg {:route :switch-booking
             :comp :soagdoe3245}]
    (t/is (= (mr/advance example-ruleset msg)
             []))))

(t/deftest event-err1
  (let [msg {:route :saoiuhg08ehn
             :error true}]
    (t/is (= (mr/advance example-ruleset msg)
             [{:route :saoiuhg08ehn
               :comp :foobar
               :error true}]))))
