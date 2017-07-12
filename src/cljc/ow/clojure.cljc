(ns ow.clojure)

(defn map-invert-coll [m]
  (letfn [(transducer [xf]
            (fn
              ([]              (xf))
              ([result]        (xf result))
              ([result [k vs]] (cond (coll? vs) (reduce #(xf result [%2 k]) nil vs)
                                     true       (xf result [vs k])))))]
    (into {} transducer m)))
