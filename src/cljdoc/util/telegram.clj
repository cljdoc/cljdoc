(ns cljdoc.util.telegram
  "Some helpers to be notified about builds queued
  so that I can investigate when/if stuff fails"
  (:require [clj-http.lite.client :as http]
            [cljdoc.config :as cfg]
            [cheshire.core :as json]))

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
           resp (http/post url {:content-type :json
                                :body (json/generate-string body)})]
       (-> resp :status)))))

(defn build-requested
  [project version build-id]
  (->> (format "Build requested for %s v%s: https://cljdoc.org/builds/%s" project version build-id)
       (send-text bot-token chat-id)))

(defn build-failed
  [{:keys [group_id artifact_id version error id]}]
  (->> (format "%c %s while processing %s/%s: https://cljdoc.org/builds/%s" (int 10060) error group_id artifact_id id)
       (send-text bot-token chat-id)))

(defn import-completed
  [{:keys [group_id artifact_id version]} git-error]
  (->> (when git-error (format "\n%c SCM issue: %s" (int 65039) git-error))
       (str (format "%c Import completed: https://cljdoc.org/d/%s/%s/%s" (int 9989) group_id artifact_id version))
       (send-text bot-token chat-id)))

(defn has-cljdoc-edn
  [scm-url]
  (->> (format "%c cljdoc.edn found in %s" (int 10024) scm-url)
       (send-text bot-token chat-id)))

(defn no-version-tag
  [project version scm-url]
  (->> (format "Untagged version for %s v%s\n%s" project version scm-url)
       (send-text bot-token chat-id)))

(comment
  ;; useful helper to get initial chat id
  (http/get (str base-url bot-token "/getUpdates")
            {:as :json})

  (send-text bot-token chat-id "hello")

  ;; 10060 :x:
  ;; 9989 :white_check_mark:
  ;; 10024 :sparkles:
  ;; 65039 :warning:

  (.codePointAt "️" 0))

