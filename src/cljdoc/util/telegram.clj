(ns cljdoc.util.telegram
  "Some helpers to be notified about builds queued
  so that I can investigate when/if stuff fails"
  (:require [aleph.http :as http]
            [cljdoc.config :as cfg]
            [jsonista.core :as json]))

(def bot-token
  (get-in (cfg/config) [:secrets :telegram :bot-token]))

(def chat-id
  (get-in (cfg/config) [:secrets :telegram :chat-id]))

(def base-url
  "https://api.telegram.org/bot")

(defn send-text
  "Sends message to the chat"
  ([token chat-id text]
   (send-text token chat-id {} text))
  ([token chat-id options text]
   (when (and token chat-id)
     (let [url  (str base-url token "/sendMessage")
           body (into {:chat_id chat-id :text text} options)
           resp @(http/post url {:content-type :json
                                 :body (json/write-value-as-string body)})]
       (-> resp :status)))))

(defn build-requested
  [project version circle-job-url]
  (->> (format "Build requested for %s v%s: %s" project version circle-job-url)
       (send-text bot-token chat-id)))

(defn import-completed
  [docs-url]
  (->> (format "Import completed: %s" docs-url)
       (send-text bot-token chat-id)))

(comment
  ;; useful helper to get initial chat id
  @(http/get (str base-url bot-token "/getUpdates")
             {:as :json})

  (send-text bot-token chat-id "hello")

  )

