(ns ow.starbuck.protocols
  (:refer-clojure :rename {send send-clj}))

(defprotocol Component
  (process [this msg])
  (answer [this resmsg]))

(defprotocol Tunnel
  (send [this msg]))
