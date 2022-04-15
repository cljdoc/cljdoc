(ns cljdoc.server.search.clojars
  "Fetch listing of artifacts from clojars."
  (:require
   [clj-http.lite.client :as http]
   [cljdoc.spec :as cljdoc-spec]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log])
  (:import
   (java.io InputStream)
   (java.util.zip GZIPInputStream)))

(defonce clojars-last-modified (atom nil))

(comment
  (reset! clojars-last-modified nil))

(defn- process-clojars-response [{:keys [headers body]}]
  {:pre [(instance? InputStream body)]}
  (with-open [in (io/reader (GZIPInputStream. body))]
    (let [artifacts     (into []
                              (comp
                               (map #(-> % edn/read-string (assoc :origin :clojars)))
                                ;; Latest Clojure Contrib libs are in Maven Central
                                ;; and thus should be loaded from there
                               (filter #(not= "org.clojure" (:group-id %))))
                              (line-seq in))
          last-modified (get headers "last-modified")]
      (log/info (str "Downloaded " (count artifacts) " artifacts from Clojars with last-modified " last-modified))
      (reset! clojars-last-modified last-modified)
      artifacts)))

(defn load-clojars-artifacts [force?]
  (try
    (let [res
          (http/get
           "https://clojars.org/repo/feed.clj.gz"
           {:throw-exceptions false
            :as               :stream
            :headers          {"If-Modified-Since" (when-not force? @clojars-last-modified)
                                ;; Avoid double-gzipping by Clojars' proxy:
                               "Accept-Encoding" ""}})]
      (case (:status res)
        304 (do
              (log/debug
               (str
                "Skipping Clojars download - no change since last one at "
                @clojars-last-modified))
              nil) ;; data not changed since the last time, do nothing
        200 (process-clojars-response res)
        (throw (ex-info "Unexpected HTTP status from Clojars" {:response res}))))
    (catch Exception e
      (log/info e "Failed to download artifacts from Clojars")
      nil)))

(s/fdef load-clojars-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))
