(ns cljdoc.util.clojars
  (:require [cljdoc.util :as util]
            [aleph.http :as http]
            [byte-streams :as bs])
  (:import (org.jsoup Jsoup)))

(defn on-clojars? [project version]
  (let [uri (format "https://repo.clojars.org/%s/%s/%s/"
                    (util/group-id project) (util/artifact-id project) version)]
    (= 200 (:status @(aleph.http/head uri {:throw-exceptions? false})))))

(defn metadata-xml-uri [project version]
  (format "https://repo.clojars.org/%s/%s/%s/maven-metadata.xml"
          (util/group-id project) (util/artifact-id project) version))

(defn resolve-snapshot [project version]
  (let [d (Jsoup/parse (bs/to-string (:body @(http/get (metadata-xml-uri project version)))))]
    (->> (.select d "versioning > snapshotVersions > snapshotVersion > value")
         (map #(.ownText %))
         (set)
         (util/assert-first))))

(defn artifact-uris
  [project version]
  {:pre [(some? project) (some? version)]}
  (if-not (on-clojars? project version)
    (throw (ex-info (format "Requested version cannot be found on Clojars: [%s %s]" project version)
                    {:project project :version version}))
    (let [version' (if (.endsWith version "-SNAPSHOT")
                     (resolve-snapshot project version)
                     version)]
      {:pom (format "https://repo.clojars.org/%s/%s/%s/%s-%s.pom"
                    (util/group-id project) (util/artifact-id project) version
                    (util/artifact-id project) version')
       :jar (format "https://repo.clojars.org/%s/%s/%s/%s-%s.jar"
                    (util/group-id project) (util/artifact-id project) version
                    (util/artifact-id project) version')})))

(comment
  @(http/head (metadata-xml-uri 'bidi "2.1.3-SNAPSHOT") {:throw-exceptions? false})

  (on-clojars? 'bidi "2.1.3-SNAPSHOT")
  (on-clojars? 'bidi "2.1.3")
  (on-clojars? 'bidi "2.1.4")

  (artifact-uris 'bidi "2.1.3-SNAPSHOT")
  (artifact-uris 'bidi "2.1.3")

  (on-clojars? 'bidi "2.1.3")
  (on-clojars? 'bidi "2.1.4")

  ;; (def f "/Users/martin/Downloads/clojars-downloads.edn")
  ;; (def f "https://clojars.org/stats/downloads-20180525.edn")

  ;; (comment
  ;;   (->> (edn/read-string (slurp f))
  ;;        (map (fn [[coord versions]]
  ;;               (let [v (first (sort-by key versions))]
  ;;                 [(conj coord (key v)) (val v)])))
  ;;        (sort-by second >)
  ;;        (drop 10)
  ;;        (take 10)
  ;;        #_(rand-nth)
  ;;        clojure.pprint/pprint))

  ;;   (def all-poms "http://repo.clojars.org/all-poms.txt")

  )
