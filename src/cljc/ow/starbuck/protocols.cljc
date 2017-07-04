(ns ow.starbuck.protocols
  (:refer-clojure :rename {send send-clj}))

(defprotocol Tunnel
  (send [this msg]))
