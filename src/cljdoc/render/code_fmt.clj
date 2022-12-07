(ns cljdoc.render.code-fmt
  "Isolate code formatting to its own namespace.
  We are using zprint for its widths support.
  It has many dials and knobs, so it is worthy of its own namespace to support testing."
  (:require [clojure.string :as string]
            [zprint.core :as zp]
            [zprint.config :as zpc]))

;; tell zprint not to look for external default configs
(zp/set-options! {:configured? true})

;; zprint applies special styling to particular function calls,
;; this is problematic for our use case, and can cause throws.
(def ^:private all-fnstyles-disabled (reduce-kv (fn [m k _v] (assoc m k :none))
                                                {}
                                                zpc/zfnstyle))

(defn snippet
  "Return formatted version of clojure code snippet in string `s`."
  [s]
  (if (string/blank? s)
    ""
    (zp/zprint-str s {:parse-string? true
                      :width 80
                      :map {:comma? false}
                      :fn-map all-fnstyles-disabled})))
