(ns ow.starbuck.client
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]]))
  (:require #?(:clj  [clojure.core.async.impl.protocols :as ap]
               :cljs [cljs.core.async.impl.protocols :as ap])
            #?(:clj  [clojure.core.async :refer [go go-loop] :as a]
               :cljs [cljs.core.async :as a])
            [ow.clojure :as owc]))

(defn trigger-route [router-ch msg]
  (a/put! router-ch msg))

(defn trigger-route-sync [router-ch component-pub listen-kw msg]
  )
