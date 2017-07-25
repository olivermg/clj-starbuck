# clj-starbuck

A Clojure library that lets you decouple your application, while making communication easy.

[![Clojars Project](https://img.shields.io/clojars/v/clj-starbuck.svg)](https://clojars.org/clj-starbuck)


## Goals

clj-starbuck helps you to...

* ...make network communications in your app (e.g. between browser and server in a webapp) completely transparent.
* ...make internal communications in your app (between different modules within your app) just as transparent.
* ...make communications between network components (e.g. browser & server) reactive.
* ...implicitly and transparently use multithreading (modules may run on any arbitrary threads).
* ...avoid dependency hell within the software you write by keeping modules independent from each other.
* ...provide all these benefits in Clojure as well as Clojurescript.


## Quick Start

### Setup

1. Define a routing ruleset:
   ```clojure
   (def ruleset
     {:routes {:book-flight
               {:transitions {nil     :booker
                              :booker :invoice-generator}}}})

   ```

2. Define the necessary core.async channels:
   ```clojure
   (require '[clojure.core.async :as a])
   (require '[ow.starbuck.impl.core-async :as ica])

   (def component-ch  (a/chan))
   (def component-pub (ica/component-pub component-ch))
   (def router-ch     (a/chan))
   ```

3. Define the components involved:
   ```clojure
   (def booker
     (ica/component "booker"                                  ;; name
                    (ica/component-sub component-pub :booker) ;; core.async sub channel
                    router-ch                                 ;; where to put result
                    (fn [this msg]                            ;; you app's business logic
                      (assoc msg :booked? true))))

   (def invoice-generator
     (ica/component "invoice-generator"
                    (ica/component-sub component-pub :invoice-generator)
                    router-ch
                    (fn [this msg]
                      (println "invoice generated")
                      msg)))
   ```

4. Build config for Router:
   ```clojure
   (require '[ow.starbuck.client :as c])

   (def config
     (c/config ruleset
               {:components {:booker booker
                             :invoice-generator invoice-generator}}))
   ```

5. Create Router:
   ```clojure
   (def router
     (ica/router config router-ch))
   ```

6. Start Router & Components:
   ```clojure
   (def router (ica/start router))
   (def booker (ica/start booker))
   (def invoice-generator (ica/start invoice-generator))
   ```

You now have a router waiting for incoming messages on `router-ch`. It will use `config` to determine the next
component for a message and put it onto `component-ch`. The corresponding component will receive the message
via its own subscription channel created via `component-sub`. After the component is done with the message,
it will be put onto `router-ch` again and the process starts again for as long as there is a next component
defined.

### Usage

#### Async

You can create and send a message asynchronously:

``` clojure
(let [msg (c/message :book-flight
                     {:from :london :to :berlin})]
  (a/put! router-ch msg))
```

The message will be picked up by the router and will then be routed through the components defined in the
config's ruleset.

#### Sync


## Basic Principles

clj-starbuck assumes that you structure your app in a way that it consists of independent modules (called components).
Those modules communicate with each other via messages. However, the components don't know anything about each other
(so they don't depend on each other). Instead, clj-starbuck provides a Router component that delivers messages to the
right components. A component processes a messages, potentially altering it and implicitly returns it back to the
Router that knows which component the message has to go to next.

### Messages

Messages are simple maps of data. As messages flow through the system, they need to maintain some info about the
routing process, so you create messages via

``` clojure
(require '[ow.starbuck.client :as sc])

(def msg1 (sc/message {:foo :bar}))
```

`{:foo :bar}` is the user data your application needs for processing its business logic, so it can be anything
(as long as it is a map on the outside).


* Components
* Units + Tunnels
* Routing table / ruleset


## Dependencies

This library itself is based on:

* core.async


## Example

TBD


## License

Copyright © 2017 Oliver Wegner

Distributed under the Apache License, Version 2.0.
