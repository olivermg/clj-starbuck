(ns ow.starbuck.protocols
  (:refer-clojure :rename {deliver deliver-clj}))

(defprotocol Component
  (deliver [this msg]))
