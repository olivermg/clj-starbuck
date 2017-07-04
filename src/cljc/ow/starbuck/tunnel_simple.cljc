(ns ow.starbuck.tunnel-simple
  (:require [ow.starbuck.protocols :as p]
            [ow.starbuck.message-router :as mr]))

(defrecord TunnelSimple [remote-ruleset]

  p/Tunnel

  (send [this msg]
    (mr/advance remote-ruleset msg)))
