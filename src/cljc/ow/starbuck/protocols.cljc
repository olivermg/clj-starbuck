(ns ow.starbuck.protocols
  (:refer-clojure :rename {send send-clj}))

(defprotocol Component
  (process [this msg]))

(defprotocol Tunnel
  (send [this msg]))
