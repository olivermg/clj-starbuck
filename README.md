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


## Principles

* Messages
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
