;;;
;;; Copyright 2016 Oliver Wegner
;;;
;;; Licensed under the Apache License, Version 2.0 (the "License");
;;; you may not use this file except in compliance with the License.
;;; You may obtain a copy of the License at
;;;
;;; http://www.apache.org/licenses/LICENSE-2.0
;;;
;;; Unless required by applicable law or agreed to in writing, software
;;; distributed under the License is distributed on an "AS IS" BASIS,
;;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;; See the License for the specific language governing permissions and
;;; limitations under the License.
;;;

(defproject clj-starbuck "2.0.0-SNAPSHOT"
  :description "Decouple your application's modules"
  :url "http://github.com/olivermg/clj-starbuck"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [com.stuartsierra/component "0.3.1"]                  ;; system + component + lifecycle management
                 [org.clojure/core.async "0.2.395"]                    ;; async communication (channels)
                 [org.toomuchcode/clara-rules "0.11.1"]                ;; rule engine (forward chaining)
                 [com.taoensso/timbre "4.10.0"]                        ;; logging facility
                 [com.taoensso/sente "1.11.0"]                         ;; websockets/ajax communications
                 [ring "1.4.0"]                                        ;; ring middlewares
                 [http-kit "2.2.0"]                                    ;; http server for websockets/ajax communications
                 ]

  :plugins [[lein-cljsbuild "1.1.6"]
            [lein-npm "0.6.2"]]

  ;;;:npm {:dependencies []}

  :source-paths ["src/clj" "src/cljc"]

  :cljsbuild {:builds
              [{:id "browser-dev"
                :source-paths ["src/cljs"]
                :figwheel true
                :compiler {:output-to             "target/browser-dev/clj-starbuck.js"
                           :output-dir            "target/browser-dev"
                           :source-map-timestamp  true
                           :print-input-delimiter true
                           :output-wrapper        true
                           :optimizations         :none
                           :pretty-print          true}}

               {:id "browser-min"
                :source-paths ["src/cljs"]
                :compiler {:output-to     "target/browser-min/clj-starbuck.js"
                           :output-dir    "target/browser-min"
                           :optimizations :advanced
                           :pretty-print  false}}

               {:id "node"
                :source-paths ["src/cljs"]
                ;;; sync this with dev/repl.clj:
                :compiler {:output-to     "target/node/clj-starbuck.js"
                           :output-dir    "target/node"
                           :target        :nodejs
                           :language-in   :ecmascript5
                           :optimizations :none
                           :main          ow.starbuck.message-router
                           }}]}

  :figwheel {:ring-handler repl/ring-handler
             :server-logfile "log/figwheel.log"}

  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.12"]
                                  [com.cemerick/piggieback "0.2.2"]
                                  [figwheel "0.5.8"]
                                  [figwheel-sidecar "0.5.8"]]
                   :plugins [[lein-figwheel "0.5.8"]]
                   :source-paths ["dev"]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]
                                  :init-ns repl}}})
