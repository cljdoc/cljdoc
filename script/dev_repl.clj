(ns dev-repl
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [lread.status-line :as status]))

;; Entry points
(defn task
  {:org.babashka/cli {:spec {:flowstorm {:alias :f
                                         :coerce :boolean
                                         :desc "Enable flowstorm"}
                             :clerk {:alias :c
                                     :coerce :boolean
                                     :desc "Enable clerk"}
                             ;; cider nrepl pass through opts
                             :host {:ref "<ADDR>"
                                    :alias :h
                                    :default "127.0.0.1"
                                    :desc "Host address"}
                             :bind {:ref "<ADDR>"
                                    :alias :b
                                    :default "127.0.0.1"
                                    :desc "Bind address"}
                             :port {:ref "<symbols>"
                                    :coerce :int
                                    :default 0
                                    :alias :p
                                    :desc "Port, 0 for auto-select"}}}}

  [{:keys [flowstorm clerk host bind port]}]
  (let [aliases (cond-> ["cli" "test" "nrepl"]
                  flowstorm (conj "flowstorm")
                  clerk (conj "clerk"))]
    (status/line :head "Launching Clojure nREPL")
    (when flowstorm
      (status/line :detail "Flowstorm support is enabled"))
    (when clerk
      (status/line :detail "Clerk support is enabled"))
    (process/exec "clj" (str "-M:" (str/join ":" aliases))
                  "-h" host
                  "-b" bind
                  "-p" port)))
