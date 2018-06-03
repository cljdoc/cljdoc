(ns cljdoc.util.repositories
  (:require [cljdoc.util :as util]
            [clojure.string :as string]
            [aleph.http :as http]
            [byte-streams :as bs]
            [jsonista.core :as json]
            [clojure.tools.logging :as log])
  (:import (org.jsoup Jsoup)
           (java.time Instant Duration)))

; TODO. Maven Central
(defn releases-since [inst]
  (let [req @(http/get "https://clojars.org/search"
                       {;:throw-exceptions? false
                        :query-params {"q" (format "at:[%s TO %s]" (str inst) (str (Instant/now)))
                                       "format" "json"
                                       "page" 1}})
        results (-> req :body bs/to-string json/read-value (get "results"))]
    (->> results
         (sort-by #(get % "created"))
         (map (fn [r]
                {:created_ts  (Instant/ofEpochMilli (Long. (get r "created")))
                 :group_id    (get r "group_name")
                 :artifact_id (get r "jar_name")
                 :version     (get r "version")})))))

(defn group-path [project]
  (string/replace (util/group-id project) #"\." "/"))

(defn metadata-xml-uri [repository project version]
  (format "%s%s/%s/%s/maven-metadata.xml"
          repository
          (group-path project)
          (util/artifact-id project)
          version))

(defn resolve-snapshot [repository project version]
  (let [{:keys [body status]} @(http/get (metadata-xml-uri repository project version)
                                         {:throw-exceptions? false})]
    (if (= 200 status)
      (let [d (Jsoup/parse (bs/to-string body))]
        (->> (.select d "versioning > snapshotVersions > snapshotVersion > value")
             (map #(.ownText %))
             (set)
             (util/assert-first)))
      version)))

(defn exists? [repository project version]
  (let [version' (if (.endsWith version "-SNAPSHOT")
                   (resolve-snapshot repository project version)
                   version)
        uri (format "%s%s/%s/%s/%s-%s.pom"
                    repository
                    (group-path project)
                    (util/artifact-id project)
                    version
                    (util/artifact-id project)
                    version')]
    (= 200 (:status @(aleph.http/get uri {:throw-exceptions? false})))))

(defn artifact-uris*
  [repository project version]
  {:pre [(some? project) (some? version)]}
  (let [version' (if (.endsWith version "-SNAPSHOT")
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

(def repositories
  {:clojars "https://repo.clojars.org/"
   :maven-central "https://search.maven.org/remotecontent?filepath="})

(defn find-artifact-repository [project version]
  (first (filter #(exists? % project version) (vals repositories))))

(defn artifact-uris [project version]
  (if-let [repository (find-artifact-repository project version)]
    (artifact-uris* repository project version)
    (throw (ex-info (format "Requested version cannot be found on Clojars or Maven Central: [%s %s]" project version)
                    {:project project :version version}))))

(comment
  (find-artifact-repository "org.clojure/clojure" "1.9.0")

  @(http/head (metadata-xml-uri (:clojars repositories) 'bidi "2.1.3-SNAPSHOT") {:throw-exceptions? false})

  (exists? (:clojars repositories) 'bidi "2.1.3-SNAPSHOT")
  (exists? (:clojars repositories) 'bidi "2.0.9-SNAPSHOT")

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
