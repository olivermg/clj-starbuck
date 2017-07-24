(ns ow.starbuck.client
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]))

(defn message [route map & {:keys [max-transitions]
                            :or {max-transitions 100}}]
  "Creates a new routable message."
  (assoc map
         ::route route
         ::component '()
         ::transition-count 0
         ::max-transitions max-transitions))

(defn make-config [ruleset components & {:keys [unit]}]
  {:unit unit
   :components components
   :ruleset ruleset})

(defn trigger-route [router-ch msg]
  (a/put! router-ch msg))

(defn trigger-route-sync [router-ch component-pub listen-kw msg]
  )
