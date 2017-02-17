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

(defproject clj-starbuck "1.0.1-SNAPSHOT"
  :description "Decouple your application's modules"
  :url "http://github.com/olivermg/clj-starbuck"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [com.stuartsierra/component "0.3.1"]                  ;; system + component + lifecycle management
                 [org.clojure/core.async "0.2.395"]                    ;; async communication (channels)
                 [org.toomuchcode/clara-rules "0.11.1"]                ;; rule engine (forward chaining)
                 [com.taoensso/timbre "4.4.0"]                         ;; logging facility
                 ]
  :plugins [[lein-cljsbuild "1.1.5"]]
  :source-paths ["src/clj" "src/cljc"]
  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src/cljc"]
                :compiler {:output-to "target/clj-starbuck.js"
                           :output-dir "target"
                           :source-map-timestamp true
                           :print-input-delimiter true
                           :output-wrapper true
                           :optimizations :none
                           :pretty-print true}}
               #_{:id "min"
                :source-paths ["src/cljc"]
                :compiler {:output-to "target/clj-starbuck.js"
                           :output-dir "target"
                           :source-map-timestamp true
                           :optimizations :advanced
                           :pretty-print false}}]})
