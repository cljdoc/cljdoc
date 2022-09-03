(ns cljdoc.util.repositories
  (:require [cljdoc.config :as config]
            [cljdoc-shared.proj :as proj]
            [clojure.string :as string]
            [clj-http.lite.client :as http]
            [clojure.java.io :as io])
  (:import (org.jsoup Jsoup)))

(defn group-path [project]
  (string/replace (proj/group-id project) #"\." "/"))

(defn version-directory-uri
  [repository project version]
  {:pre [(string? repository)]}
  (format "%s%s/%s/%s/" repository (group-path project) (proj/artifact-id project) version))

(defn artifact-uri
  ([suffix repository project version]
   (artifact-uri suffix repository project version version))
  ([suffix repository project version actual-version]
   (format "%s%s-%s.%s"
           (version-directory-uri repository project version)
           (proj/artifact-id project)
           actual-version
           suffix)))

(def jar-uri (partial artifact-uri "jar"))
(def pom-uri (partial artifact-uri "pom"))

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
           (proj/artifact-id project)))
  ([repository project version]
   (str (version-directory-uri repository project version)
        "maven-metadata.xml")))

(defn resolve-snapshot [repository project version]
  (let [{:keys [body status]} (http/get (metadata-xml-uri repository project version)
                                        {:throw-exceptions false})]
    (if (= 200 status)
      (let [d (Jsoup/parse body)
            timestamp (.ownText (first (.select d "versioning > snapshot > timestamp")))
            build-num (.ownText (first (.select d "versioning > snapshot > buildNumber")))]
        (assert timestamp "Could not extract SNAPSHOT timestamp from metadata.xml")
        (assert build-num "Could not extract SNAPSHOT buildNumber from metadata.xml")
        (string/replace version #"-SNAPSHOT$" (str "-" timestamp "-" build-num)))
      version)))

(defn snapshot-version?
  [version]
  (.endsWith version "-SNAPSHOT"))

(defn exists?
  ([repository project]
   (let [uri (metadata-xml-uri repository project)]
     (= 200 (:status (http/head uri {:throw-exceptions false})))))
  ([repository project version]
   (let [uri (if (snapshot-version? version)
               (metadata-xml-uri repository project version)
               (pom-uri repository project version))]
     (= 200 (:status (http/head uri {:throw-exceptions false}))))))

(defn artifact-uris*
  [repository project version]
  {:pre [(string? repository) (some? project) (some? version)]}
  (let [version' (if (and (snapshot-version? version)
                          (.startsWith repository "http"))
                   (resolve-snapshot repository project version)
                   version)]
    {:pom (pom-uri repository project version version')
     :jar (jar-uri repository project version version')}))

(defn find-artifact-repository
  ([project]
   (reduce #(when (exists? (:url %2) project) (reduced (:url %2))) [] (config/maven-repositories)))
  ([project version]
   (reduce #(when (exists? (:url %2) project version) (reduced (:url %2))) [] (config/maven-repositories))))

(defn artifact-uris [project version]
  (if-let [repository (find-artifact-repository project version)]
    (artifact-uris* repository project version)
    (throw (ex-info (format "Requested version cannot be found in configured repositories: [%s %s]" project version)
                    {:project project :version version}))))

(defn assert-first [[x & rest :as xs]]
  (if (empty? rest)
    x
    (throw (ex-info "Expected collection with one item, got multiple"
                    {:coll xs}))))

(defn latest-release-version [project]
  (if-let [repository (find-artifact-repository project)]
    (let [{:keys [body status]} (http/get (metadata-xml-uri repository project))]
      (when (= 200 status)
        (let [d (Jsoup/parse body)]
          ;; for some very old uploads to clojars the structure of maven-metadata.xml
          ;; is slightly different: https://repo.clojars.org/clansi/clansi/maven-metadata.xml
          (or (some-> (.select d "metadata > version") first (.ownText))
              (->> (.select d "metadata > versioning > release")
                   (map #(.ownText %))
                   assert-first)))))
    (throw (ex-info (format "Requested project cannot be found in configured repositories: %s" project)
                    {:project project}))))

(defn local-uris [project version]
  (let [repo (str (System/getProperty "user.home") "/.m2/repository/")
        uris (artifact-uris* repo project version)]
    (when (.exists (io/file (:jar uris)))
      uris)))

(defn get-pom-xml
  "Fetches contents of pom.xml for a particular artifact version."
  [project version]
  (if-let [local-pom (:pom (local-uris project version))]
    (slurp local-pom)
    (-> (artifact-uris project version) :pom http/get :body)))

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
  (clojure.core.memoize/memo-clear! get-pom-xml '("org.clojure/clojure" "1.9.0")))
