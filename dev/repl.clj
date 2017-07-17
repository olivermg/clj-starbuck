(ns repl
  (:require [cljs.repl.node :as n]
            [cljs.build.api :as a]
            [cemerick.piggieback :as p]
            [figwheel-sidecar.repl-api :as f]))

(defonce http-handler nil) ;; for figwheel (referenced from project.clj)

(defn start-figwheel []
  (f/start-figwheel! "browser-dev"))

(defn stop-figwheel []
  (f/stop-figwheel!))

(defn start-browser-repl []
  (f/cljs-repl))

(def node-cfg
  ;;; sync this with project.clj:
  {:output-to     "target/node/clj-starbuck.js"
   :output-dir    "target/node"
   :target        :nodejs
   :language-in   :ecmascript5
   :optimizations :none
   ;;;:main          ow.starbuck.message-router
   })

(defn start-node-repl []
  (a/build "src" node-cfg)
  (apply p/cljs-repl (n/repl-env)
         (flatten (vec node-cfg))))
