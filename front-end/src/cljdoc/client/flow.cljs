(ns cljdoc.client.flow)

(defn debounced
  "Debounce funtion `f` with a `delay-ms`, result of `f` is returned in promise."
  [delay-ms f]
  (let [timer-id (atom nil)]
    (fn [& args]
      (when @timer-id
        (js/clearTimeout @timer-id))
      (js/Promise.
       (fn [resolve reject]
         (reset! timer-id
                 (js/setTimeout
                  (fn []
                    (try
                      (-> (apply f args) resolve)
                      (catch :default e
                        (reject e))
                      (finally
                        (reset! timer-id nil)))) delay-ms)))))))
