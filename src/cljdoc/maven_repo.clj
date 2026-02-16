(ns cljdoc.maven-repo
  (:require [cljdoc-shared.proj :as proj]
            [cljdoc.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Element)))

(set! *warn-on-reflection* true)

(defn group-path [project]
  (string/replace (proj/group-id project) #"\." "/"))

(defn- version-directory-uri
  [repository project version]
  {:pre [(string? repository)]}
  (format "%s%s/%s/%s/" repository (group-path project) (proj/artifact-id project) version))

(defn- artifact-uri
  ([suffix repository project version]
   (artifact-uri suffix repository project version version))
  ([suffix repository project version actual-version]
   (format "%s%s-%s.%s"
           (version-directory-uri repository project version)
           (proj/artifact-id project)
           actual-version
           suffix)))

(def ^:private jar-uri (partial artifact-uri "jar"))
(def ^:private pom-uri (partial artifact-uri "pom"))

(defn- metadata-xml-uri
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

(defn- resolve-snapshot [repository project version]
  (let [{:keys [body status]} (http/get (metadata-xml-uri repository project version)
                                        {:throw false})]
    (if (= 200 status)
      (let [d (Jsoup/parse ^String body)
            timestamp (.ownText ^Element (first (.select d "versioning > snapshot > timestamp")))
            build-num (.ownText ^Element (first (.select d "versioning > snapshot > buildNumber")))]
        (assert timestamp "Could not extract SNAPSHOT timestamp from metadata.xml")
        (assert build-num "Could not extract SNAPSHOT buildNumber from metadata.xml")
        (string/replace version #"-SNAPSHOT$" (str "-" timestamp "-" build-num)))
      version)))

(defn- snapshot-version?
  [version]
  (string/ends-with? version "-SNAPSHOT"))

(defn resolve-artifact
  ([repository project]
   (let [uri (metadata-xml-uri repository project)]
     (http/head uri {:throw false})))
  ([repository project version]
   (let [uri (if (snapshot-version? version)
               (metadata-xml-uri repository project version)
               (pom-uri repository project version))]
     (http/head uri {:throw false}))))

(defn exists?
  ([repository project]
   (= 200 (:status (resolve-artifact repository project))))
  ([repository project version]
   (= 200 (:status (resolve-artifact repository project version)))))

(defn- artifact-uris*
  [repository project version]
  {:pre [(string? repository) (some? project) (some? version)]}
  (let [version' (if (and (snapshot-version? version)
                          (string/starts-with? repository "http"))
                   (resolve-snapshot repository project version)
                   version)]
    {:pom (pom-uri repository project version version')
     :jar (jar-uri repository project version version')}))

(defn find-artifact-repository-by-project
  [maven-repos project]
  (reduce #(when (exists? (:url %2) project)
             (reduced (:url %2)))
          []
          maven-repos))

(defn find-artifact-repository
  [maven-repos project version]
  (reduce #(when (exists? (:url %2) project version)
             (reduced (:url %2)))
          []
          maven-repos))

(defn artifact-uris [maven-repos project version]
  (when-let [repository (find-artifact-repository maven-repos project version)]
    (artifact-uris* repository project version)))

(defn assert-first [[x & rest :as xs]]
  (if (empty? rest)
    x
    (throw (ex-info "Expected collection with one item, got multiple"
                    {:coll xs}))))

(defn latest-release-version
  "Return latest known release for `project`.
  When `project` not found return `nil`."
  [maven-repos project]
  (when-let [repository (find-artifact-repository-by-project maven-repos project)]
    (let [{:keys [body status]} (http/get (metadata-xml-uri repository project))]
      (when (= 200 status)
        (let [d (Jsoup/parse ^String body)]
          ;; for some very old uploads to clojars the structure of maven-metadata.xml
          ;; is slightly different: https://repo.clojars.org/clansi/clansi/maven-metadata.xml
          (or (some-> (.select d "metadata > version") .first (.ownText))
              (->> (.select d "metadata > versioning > release")
                   (map (fn [^Element e] (.ownText e)))
                   assert-first)))))))

(defn local-uris [project version]
  (let [repo (str (System/getProperty "user.home") "/.m2/repository/")
        uris (artifact-uris* repo project version)]
    (when (.exists (io/file (:jar uris)))
      uris)))

(defn- get-pom-xml
  "Fetches contents of pom.xml for a particular artifact version."
  [maven-repos project version]
  (if-let [local-pom (:pom (local-uris project version))]
    (slurp local-pom)
    (some-> (artifact-uris maven-repos project version) :pom http/get :body)))

(defn pom-fetcher [maven-repos]
  (fn [project version]
    (get-pom-xml maven-repos project version)))

(comment
  (config/maven-repos)
  ;; => [{:id "clojars", :url "https://repo.clojars.org/"}
  ;;     {:id "central", :url "https://repo.maven.apache.org/maven2/"}]

  (artifact-uris* "https://repo.clojars.org/" "com.mouryaravi/faraday" "1.11.1+protocol")
  ;; => {:pom
  ;;     "https://repo.clojars.org/com/mouryaravi/faraday/1.11.1+protocol/faraday-1.11.1+protocol.pom",
  ;;     :jar
  ;;     "https://repo.clojars.org/com/mouryaravi/faraday/1.11.1+protocol/faraday-1.11.1+protocol.jar"}

  (exists? "https://repo.clojars.org/" "com.mouryaravi/faraday" "1.11.1+protocol")
  ;; => false
  (exists? "https://repo.clojars.org/" "com.mouryaravi/faraday")
  ;; => true

  (resolve-artifact "https://repo.clojars.org/" "com.mouryaravi/faraday" "1.11.1+protocol")
  ;; => {:status 200,
  ;;     :body "",
  ;;     :version :http1.1,
  ;;     :headers
  ;;     {"x-cache" "MISS",
  ;;      "x-timer" "S1732893942.993537,VS0,VE129",
  ;;      "server" "AmazonS3",
  ;;      "age" "0",
  ;;      "via" "1.1 varnish",
  ;;      "content-type" "application/xml",
  ;;      "content-length" "4402",
  ;;      "connection" "keep-alive",
  ;;      "accept-ranges" "bytes",
  ;;      "etag" "\"d8db8433db4306839983a9d50f0a6da3\"",
  ;;      "x-cache-hits" "0",
  ;;      "x-amz-request-id" "59ZCN4J9THYMZMPX",
  ;;      "date" "Fri, 29 Nov 2024 15:25:42 GMT",
  ;;      "last-modified" "Wed, 21 Oct 2020 03:15:18 GMT",
  ;;      "x-amz-id-2"
  ;;      "tH7VQJw8BSG1oeyHFSiJVGpozkF56YToI2x/GU3UrAdO0ftfh45Jwaa9ayjeZI7O1KLzXeajtbQ=",
  ;;      "x-served-by" "cache-lga21932-LGA"},
  ;;     :uri
  ;;     #object[java.net.URI 0x4bcce9a0 "https://repo.clojars.org/com/mouryaravi/faraday/1.11.1+protocol/faraday-1.11.1+protocol.pom"],
  ;;     :request
  ;;     {:headers
  ;;      {:accept "*/*",
  ;;       :accept-encoding ["gzip" "deflate"],
  ;;       :user-agent "babashka.http-client/0.4.21"},
  ;;      :throw false,
  ;;      :uri
  ;;      #object[java.net.URI 0x4bcce9a0 "https://repo.clojars.org/com/mouryaravi/faraday/1.11.1+protocol/faraday-1.11.1+protocol.pom"],
  ;;      :method :head}}

  (find-artifact-repository "com.mouryaravi/faraday" "1.11.1+protocol")
  ;; => "https://repo.clojars.org/"

  (artifact-uris "com.mouryaravi/faraday" "1.11.1+protocol")
  ;; => {:pom
  ;;     "https://repo.clojars.org/com/mouryaravi/faraday/1.11.1+protocol/faraday-1.11.1+protocol.pom",
  ;;     :jar
  ;;     "https://repo.clojars.org/com/mouryaravi/faraday/1.11.1+protocol/faraday-1.11.1+protocol.jar"}

  (find-artifact-repository "org.clojure/clojure" "1.9.0")
  ;; => "https://repo.maven.apache.org/maven2/"

  (artifact-uris "org.clojure/clojure" "1.9.0")
  ;; => {:pom
  ;;     "https://repo.maven.apache.org/maven2/org/clojure/clojure/1.9.0/clojure-1.9.0.pom",
  ;;     :jar
  ;;     "https://repo.maven.apache.org/maven2/org/clojure/clojure/1.9.0/clojure-1.9.0.jar"}

  (latest-release-version "org.clojure/clojure")
  ;; => "1.12.0"

  (metadata-xml-uri "https://repo.clojars.org/" 'bidi "2.1.3-SNAPSHOT")
  ;; => "https://repo.clojars.org/bidi/bidi/2.1.3-SNAPSHOT/maven-metadata.xml"

  (find-artifact-repository 'bidi "2.1.3")
  ;; => "https://repo.clojars.org/"

  (find-artifact-repository 'bidi "2.1.4")
  ;; => "https://repo.clojars.org/"

  (artifact-uris 'bidi "2.1.3-SNAPSHOT")
  ;; => nil

  (artifact-uris 'bidi "2.0.9-SNAPSHOT")
  ;; => {:pom
  ;;     "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.pom",
  ;;     :jar
  ;;     "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.jar"}

  (artifact-uris 'bidi "2.1.3")
  ;; => {:pom "https://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.pom",
  ;;     :jar "https://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.jar"}

  (find-artifact-repository 'bidi "2.1.3")
  ;; => "https://repo.clojars.org/"

  (find-artifact-repository 'bidi "2.1.4")
  ;; => "https://repo.clojars.org/"

  (find-artifact-repository 'com.bhauman/spell-spec "0.1.0")
  ;; => "https://repo.clojars.org/"

  (time (get-pom-xml "org.clojure/clojure" "1.9.0"))

  (clojure.core.memoize/memo-clear! get-pom-xml '("org.clojure/clojure" "1.9.0"))

  :eoc)
