(ns cljdoc.client.flow)

(warn-on-lazy-reusage!)

(defn debounced
  "Debounce funtion `f` with a `delay-ms`."
  [delay-ms f]
  (let [timer-id (atom nil)]
    (fn [& args]
      (when @timer-id
        (js/clearTimeout @timer-id))
      (reset! timer-id
              (js/setTimeout
               #(apply f args)
               delay-ms)))))
