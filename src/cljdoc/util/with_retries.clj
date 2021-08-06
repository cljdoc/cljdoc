(ns cljdoc.util.with-retries)

(def ^:dynamic *default-delay* 250)

(defn do-retryable
  [{:keys [attempt attempts retry-on delay on-failure on-retry]
    :or {attempt 1}
    :as opts} retryable-fn]
  (try
    {::value (retryable-fn)}
    (catch Exception e
      (if (and (some #(instance? % e) retry-on)
               (< attempt attempts))
        (do
          (Thread/sleep (* attempt delay))
          (on-retry e)
          #(do-retryable (assoc opts :attempt (inc attempt)) retryable-fn))
        (do
          (on-failure e)
          nil)))))

(defmacro with-retries
  [{:keys [attempts retry-on delay on-failure on-retry]
    :or {attempts 3
         retry-on [Exception]
         on-retry (fn [_])
         delay *default-delay*
         on-failure #(throw %)}}
   & retryable-body]
  `(::value (trampoline do-retryable ~{:attempts attempts
                                       :retry-on retry-on
                                       :on-retry on-retry
                                       :delay delay
                                       :on-failure on-failure} #(do ~@retryable-body))))
