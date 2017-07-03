(ns ow.starbuck.component-dispatcher
  #?(:clj  (:require [clojure.core.async.impl.protocols :as ap]
                     [clojure.core.async :as a])
     :cljs (:require [cljs.core.async.impl.protocols :as ap]
                     [cljs.core.async :as a])))

(defn dispatch [msg]
  "Dispatches msg to component if it knows how. This depends on what kind
of value the component in the msg is (which has presumably been set by
ow.starbuck.router/advance before). Currently there are three possible outcomes:
 1. If component is a fn, the fn will be called with the msg as argument.
 2. If component is a core.async channel, msg will be put! on it.
 3. Otherwise, no dispatch is being made.

This function signals if any dispatch has been done by returning a 2-tuple.
The first element is a boolean value:
 true:  A dispatch has been made. The second value of the tuple is the direct
        result of that dispatching process.
 false: No dispatch has been made. The second value of the tuple is just
        the original msg."
  (let [component (:ow.starbuck.router/component msg)]
    (cond (fn? component)                   [true  (component msg)]
          (satisfies? ap/Channel component) [true  (a/put! component msg)]
          true                              [false msg])))
