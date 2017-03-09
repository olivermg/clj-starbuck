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
                           nil :database-reader

                           ;;; you can provide a map instead of a keyword.
                           ;;; :next defines the next component. it can be a keyword but also a function that
                           ;;;       returns a keyword.
                           ;;; :postprocess is an optional function that can alter the msg after routing.
                           :database-reader {:transform (fn [msg] (assoc msg :transformed true))
                                             :next (fn [msg] :converter)}

                           ;;; :preprocess is an optional function that can alter the msg before routing.
                           ;;; :next can also be a sequence of next components. that will multiply resulting
                           ;;;       messages. in this case, it will generate messages to the components
                           ;;;       :logger and :http-server.
                           :converter [{:transform (fn [msg] (assoc msg :transformed 1))
                                        :next :foo}
                                       {:transform (fn [msg] (assoc msg :transformed 2))
                                        :next (fn [msg] :bar)}
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
             ;;; :abort? (if set to true) will throw away the original message. only the
             ;;;         event-handler message will be routed.
             :event-handlers {:error {:next :http-server
                                      :abort? true}}}}

   :event-handlers {:error :foobar
                    :notify (fn [msg] :notifier)
                    :pending-mails [:logger (fn [msg] :mailer)]}})

(t/deftest route1
  (let [msg {:route :switch-booking}]
    (t/is (= (mr/advance msg example-ruleset)
             [{:route :switch-booking
               :comp :database-reader
               :starbuck/route-count 1}]))))

(t/deftest route2
  (let [msg {:route :switch-booking
             :comp :database-reader
             :starbuck/route-count 1}]
    (t/is (= (mr/advance msg example-ruleset)
             [{:route :switch-booking
               :comp :converter
               :starbuck/route-count 2
               :transformed true}]))))

(t/deftest route3
  (let [msg {:route :switch-booking
             :comp :converter
             :starbuck/route-count 2}]
    (t/is (= (mr/advance msg example-ruleset)
             [{:route :switch-booking
               :comp :foo
               :starbuck/route-count 3
               :transformed 1}
              {:route :switch-booking
               :comp :bar
               :starbuck/route-count 3
               :transformed 2}
              {:route :switch-booking
               :comp :logger
               :starbuck/route-count 3
               :transformed 3}
              {:route :switch-booking
               :comp :http-server
               :starbuck/route-count 3
               :transformed 3}]))))

(t/deftest route4
  (let [msg {:route :switch-booking
             :comp :logger}]
    (t/is (= (mr/advance msg example-ruleset)
             []))))

(t/deftest route5
  (let [msg {:route :switch-booking
             :comp :http-server}]
    (t/is (= (mr/advance msg example-ruleset)
             []))))

(t/deftest route6
  (let [msg {:route :switch-booking
             :comp :soaihgfodser}]
    (t/is (= (mr/advance msg example-ruleset)
             []))))

(t/deftest event1
  (let [msg {:route :switch-booking
             :notify true}]
    (t/is (= (mr/advance msg example-ruleset)
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
    (t/is (= (mr/advance msg example-ruleset)
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
    (t/is (= (mr/advance msg example-ruleset)
             [{:route :switch-booking
               :comp :http-server
               :error true}]))))

(t/deftest route-err1
  (let [msg {:route :98hosedgosg}]
    (t/is (= (mr/advance msg example-ruleset)
             []))))

(t/deftest route-err2
  (let [msg {:route :switch-booking
             :comp :soagdoe3245}]
    (t/is (= (mr/advance msg example-ruleset)
             []))))

(t/deftest event-err1
  (let [msg {:route :saoiuhg08ehn
             :error true}]
    (t/is (= (mr/advance msg example-ruleset)
             [{:route :saoiuhg08ehn
               :comp :foobar
               :error true}]))))
