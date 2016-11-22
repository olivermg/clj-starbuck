# clj-starbuck

A Clojure library that lets you decouple the modules in your application.

[![Clojars Project](https://img.shields.io/clojars/v/clj-starbuck.svg)](https://clojars.org/clj-starbuck)

## Motivation

* Avoid dependency hell within the software you write!

## Idea

### Inspiration

Use queueing to make modules/components of your application independent from each other.
One component received data, processes it (maybe with side effects) and then sends this data to the queue
which will result in the data being delivered to the next component. And so on.

### Thoughts

* Queues are very static. You sometimes want to be flexible in where to route your data next, i.e.
  dynamic routing.
* An application can have many routes.
* Components may be part of multiple routes.
* Components must not know anything about who its neighbours in its route are.
* That means we need some other mechanism to decided where to route to.

## Usage

This library assumes that you're using:

* component

This library itself is based on:

* core.async
* component
* clara.rules

## Example

### Messageroutes

Let's start with the meat. You can define a routing system via a few starbuck functions and macros.

A simple example is the following which defines two routes:

``` clojure
(ns your.messageroutes
  (:require [clara.rules :refer :all] ;; seems to be necessary for whatever reason
            [ow.starbuck.message-router :refer [defroutes within-route transition on]]))

(defroutes

  ;;; define a route that routes a request through comp1, comp2 & comp3:
  (within-route :route-123
                (transition nil              :comp1-req)
                (transition :comp1-req       :comp2-req)
                (transition :comp2-req       :comp3-req)
                (transition :comp3-req       :result))

  ;;; define a route that routes a request through comp1 & comp3:
  (within-route :route-13
                (transition nil              :comp1-req)
                (transition :comp1-req       :comp3-req)
                (transition :comp3-req       :result)))
```

### Components

A component will be defined like this:

``` clojure
(ns your.component1
  (:require [ow.starbuck.core :refer [defcomponentrecord]]))

;;; this is the function that does all the work you want this component to do:
(defn- process [this req]
  (assoc req :data-by-comp1 "foo"))

;;; define a starbuck component:
(defcomponentrecord Comp1)

;;; a custom constructor that creates instances of Comp1:
(defn new-comp [ch-in ch-out]
  (map->Comp1 {:processfn process  ;; incoming requests get passed into this fn
               :ch-in ch-in        ;; the channel this component should listen on for incoming requests
               :ch-out ch-out      ;; the channel this component will put results on
              }))
```

### System

``` clojure
(ns your.system
  (:require [clojure.core.async :refer [chan pub sub]]
            [com.stuartsierra.component :refer [system-map]
            [your.httpservice :as http]
            [your.component1 :as comp1]
            [your.component2 :as comp2]
            [your.component3 :as comp3]
            [ow.starbuck.message-router :as sbmr])

(defn new-sys [config]

  (let [routing-requests (chan 100)     ;; channel that receives requests awaiting routing
        component-requests (chan 100)   ;; channel that receives requests that have been routed
        ;;; and we need a pub channel, as component will be subscribing to their topics (always in :comp):
        component-requests-pub (pub component-requests :comp)]

    (system-map

     :message-router (sbmr/new-comp
                       routing-requests       ;; message-router listens for all requests that want to be routed
                       component-requests     ;; and will put them onto this channel after routing has happened
                       'your.messageroutes)   ;; this is the namespace it will look for your route definitions in

     :http-server (http/new-comp
                    (sub component-requests-pub :result (chan 10))
                    routing-requests)

     :comp1 (comp1/new-comp
              (sub component-requests-pub :comp1-req (chan 10))
              routing-requests)

     :comp2 (comp1/new-comp
              (sub component-requests-pub :comp2-req (chan 10))
              routing-requests)

     :comp3 (comp1/new-comp
              (sub component-requests-pub :comp3-req (chan 10))
              routing-requests))))
```

## License

Copyright © 2016 Oliver Wegner

Distributed under the Apache License, Version 2.0.
