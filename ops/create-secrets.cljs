#!/usr/bin/env lumo

(js/require "process")

(require '[clojure.string :as string]
         'clojure.pprint)

(defn tf-val
  [tf-outputs k]
  {:post [(string? %)]}
  (get-in tf-outputs [k "value"]))

(defn build-edn [tf-outputs]
  (->> {:circle-ci {:api-token js/process.env.CIRCLE_API_TOKEN
                    :builder-project js/process.env.CIRCLE_BUILDER_PROJECT}
        :sentry {:dsn js/process.env.SENTRY_DSN}
        :telegram {:bot-token js/process.env.TELEGRAM_BOT_TOKEN
                   :chat-id js/process.env.TELEGRAM_CHAT_ID}}))

(def stdinput (atom []))

(.setEncoding js/process.stdin "utf8")

(.on js/process.stdin "data"
     (fn [data]
       (swap! stdinput conj data)))

(.on js/process.stdin "end"
     (fn []
       (clojure.pprint/pprint
        (->> (string/join @stdinput)
             (js/JSON.parse)
             js->clj
             build-edn))))
