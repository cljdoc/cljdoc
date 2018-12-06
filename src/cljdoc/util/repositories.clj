(ns cljdoc.util.repositories
  (:require [cljdoc.util :as util]
            [cljdoc.config :as config]
            [clojure.string :as string]
            [clj-http.lite.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [cljdoc.config :as cfg]
            [cljdoc.util.sqlite-cache :as sqlite-cache])
  (:import (org.jsoup Jsoup)
           (java.time Instant Duration)))

; TODO. Maven Central
(defn releases-since [inst]
  (let [req (http/get "https://clojars.org/search"
                      {;:throw-exceptions? false
                       :query-params {"q" (format "at:[%s TO %s]" (str inst) (str (Instant/now)))
                                      "format" "json"
                                      "page" 1}})
        results (-> req :body json/parse-string (get "results"))]
    (->> results
         (sort-by #(get % "created"))
         (map (fn [r]
                {:created_ts  (Instant/ofEpochMilli (Long. (get r "created")))
                 :group_id    (get r "group_name")
                 :artifact_id (get r "jar_name")
                 :version     (get r "version")})))))

(defn group-path [project]
  (string/replace (util/group-id project) #"\." "/"))

(defn version-directory-uri
  [repository project version]
  {:pre [(string? repository)]}
  (format "%s%s/%s/%s/" repository (group-path project) (util/artifact-id project) version))

(defn metadata-xml-uri
  "Returns a URI to read metadata for the `project`.

  For example:

  ```
  (metadata-xml-uri \"https://repo.clojars.org/\" 'bidi)
  => https://repo.clojars.org/bidi/bidi/maven-metadata.xml

  (metadata-xml-uri \"https://repo.clojars.org/\" 'bidi \"2.1.3-SNAPSHOT\")
  => https://repo.clojars.org/bidi/bidi/2.1.3-SNAPSHOT/maven-metadata.xml
  ```

  `version` as the third argument should only be provided for SNAPSHOTS.
  URIs with non snapshot versions will result in 404.
  "
  ([repository project]
   (assert (string? repository))
   (format "%s%s/%s/maven-metadata.xml"
           repository
           (group-path project)
           (util/artifact-id project)))
  ([repository project version]
   (str (version-directory-uri repository project version)
        "maven-metadata.xml")))

(defn resolve-snapshot [repository project version]
  (let [{:keys [body status]} (http/get (metadata-xml-uri repository project version)
                                        {:throw-exceptions false})]
    (if (= 200 status)
      (let [d (Jsoup/parse body)]
        (->> (.select d "versioning > snapshotVersions > snapshotVersion > value")
             (map #(.ownText %))
             (set)
             (util/assert-first)))
      version)))

(defn exists?
  ([repository project]
   (let [uri (metadata-xml-uri repository project)]
     (= 200 (:status (http/head uri {:throw-exceptions false})))))
  ([repository project version]
   (let [uri (version-directory-uri repository project version)]
     (= 200 (:status (http/head uri {:throw-exceptions false}))))))

(defn artifact-uris*
  [repository project version]
  {:pre [(string? repository) (some? project) (some? version)]}
  (let [version' (if (and (.endsWith version "-SNAPSHOT")
                          (.startsWith repository "http"))
                   (resolve-snapshot repository project version)
                   version)]
    {:pom (format "%s%s/%s/%s/%s-%s.pom"
                  repository
                  (group-path project)
                  (util/artifact-id project)
                  version
                  (util/artifact-id project)
                  version')
     :jar (format "%s%s/%s/%s/%s-%s.jar"
                  repository
                  (group-path project)
                  (util/artifact-id project)
                  version
                  (util/artifact-id project)
                  version')}))

(defn find-artifact-repository
  ([project]
   (reduce #(when (exists? (:url %2) project) (reduced (:url %2))) [] (config/maven-repositories)))
  ([project version]
   (reduce #(when (exists? (:url %2) project version) (reduced (:url %2))) [] (config/maven-repositories))))

(defn artifact-uris [project version]
  (if-let [repository (find-artifact-repository project version)]
    (artifact-uris* repository project version)
    (throw (ex-info (format "Requested version cannot be found on Clojars or Maven Central: [%s %s]" project version)
                    {:project project :version version}))))

(defn latest-release-version [project]
  (if-let [repository (find-artifact-repository project)]
    (let [{:keys [body status]} (http/get (metadata-xml-uri repository project))]
      (when (= 200 status)
        (let [d (Jsoup/parse body)]
          (->> (.select d "metadata > versioning > release")
               (map #(.ownText %))
               (util/assert-first)))))
    (throw (ex-info (format "Requested project cannot be found on Clojars or Maven Central: %s" project)
                    {:project project}))))

(defn local-uris [project version]
  (let [repo (str (System/getProperty "user.home") "/.m2/repository/")
        uris (artifact-uris* repo project version)]
    (when (.exists (io/file (:jar uris)))
      uris)))

(def get-pom-xml
  "Fetches contents of pom.xml. Memoize it in sqllite."
  (sqlite-cache/memo-sqlite
   (fn [repo version]
     (-> (artifact-uris repo version)
         :pom
         http/get
         :body))
   (assoc (cfg/cache (cfg/config))
          :key-prefix         "get-pom-xml"
          :serialize-fn       nippy/freeze
          :deserialize-fn     nippy/thaw)))

(comment
  (find-artifact-repository "org.clojure/clojure" "1.9.0")
  (artifact-uris "org.clojure/clojure" "1.9.0")

  (latest-release-version "org.clojure/clojure")

  (metadata-xml-uri "https://repo.clojars.org/" 'bidi "2.1.3-SNAPSHOT")

  (find-artifact-repository 'bidi "2.1.3")
  (find-artifact-repository 'bidi "2.1.4")

  (artifact-uris 'bidi "2.1.3-SNAPSHOT")
  (artifact-uris 'bidi "2.0.9-SNAPSHOT")
  (artifact-uris 'bidi "2.1.3")

  (find-artifact-repository 'bidi "2.1.3")
  (find-artifact-repository 'bidi "2.1.4")

  (find-artifact-repository 'com.bhauman/spell-spec "0.1.0")

  (def d
    (cljdoc.util.pom/parse (slurp (:pom (artifact-uris 'metosin/reitit "0.1.2-SNAPSHOT")))))

  (cljdoc.util.pom/scm-info d)

  (releases-since (.minus (Instant/now) (Duration/ofHours 12)))

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

  (time (get-pom-xml "org.clojure/clojure" "1.9.0"))
  (clojure.core.memoize/memo-clear! get-pom-xml '("org.clojure/clojure" "1.9.0"))

  )
