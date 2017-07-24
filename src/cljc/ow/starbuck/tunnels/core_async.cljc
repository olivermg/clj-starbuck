(ns ow.starbuck.tunnels.core-async
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require #?(:clj  [clojure.core.async :refer [go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.starbuck.protocols :as p]))

(defrecord TunnelCoreAsync [output-ch]

  p/Tunnel

  (send [this msg]
    (a/put! output-ch msg)))

(defn tunnel [output-ch]
  (map->TunnelCoreAsync {:output-ch output-ch}))
