#!/usr/bin/env lumo

(js/require "process")

(require '[clojure.string :as string]
         'clojure.pprint)

(defn tf-val
  [tf-outputs k]
  {:post [(string? %)]}
  (get-in tf-outputs [k "value"]))

(defn build-edn [tf-outputs]
  (->> {:aws {:access-key (tf-val tf-outputs "bucket_user_access_key")
              :secret-key (tf-val tf-outputs "bucket_user_secret_key")
              :s3-bucket-name (tf-val tf-outputs "bucket_name")
              :cloudfront-id (tf-val tf-outputs "cloudfront_id")
              :cloudfront-url (tf-val tf-outputs "cloudfront_url")
              :r53-hosted-zone (tf-val tf-outputs "cloudfront_url")}
        :circle-ci {:api-token js/process.env.CIRCLE_API_TOKEN
                    :builder-project js/process.env.CIRCLE_BUILDER_PROJECT}}))

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
