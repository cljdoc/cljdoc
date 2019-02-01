(ns cljdoc.util.pom
  "Functions to parse POM files and extract information from them."
  (:require [clojure.string :as string]
            [cljdoc.util :as util])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Document)))

(defn parse [pom-str]
  (Jsoup/parse pom-str))

(defn jsoup? [x]
  (instance? Document x))

(defn- text [^Jsoup doc sel]
  (when-let [t (some-> (.select doc sel) (first) (.ownText))]
    (when-not (string/blank? t) t)))

(defn licenses [^Jsoup doc]
  (for [l (.select doc "project > licenses > license")]
    {:name (text l "name")
     :url  (text l "url")}))

(defn scm-info [^Jsoup doc]
  {:url (text doc "project > scm > url")
   :sha (text doc "project > scm > tag")})

(defn managed-deps
  "Like [[dependencies]] but instead look for dependencies within the <dependencyManagement>
  key. This key is sometimes used by projects to specify dependency versions in a central place
  instead of repeating them in each .pom file.

  See [the official Maven docs](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management)."
  [^Jsoup doc]
  (for [d (.select doc "project > dependencyManagement > dependencies > dependency")]
    {:group-id    (text d "groupId")
     :artifact-id (text d "artifactId")
     :version     (text d "version")}))

(defn dependencies [^Jsoup doc]
  (for [d (.select doc "project > dependencies > dependency")]
    {:group-id    (text d "groupId")
     :artifact-id (text d "artifactId")
     :version     (text d "version")
     :scope       (text d "scope")
     :optional    (text d "optional")}))

(defn dependencies-with-versions
  "Do a merge of regular ([[dependencies]]) and managed dependencies ([[managed-deps]])
  where dependencies returned by [[managed-deps]] win. The goal here is to ensure that
  all dependencies have a non-nil `:version`, which is not always the case when using
  plain [[dependencies]]."
  [^Jsoup doc]
  (->> (into (managed-deps doc) (dependencies doc))
       (util/index-by (juxt :group-id :artifact-id))
       (vals)))

(defn repositories [^Jsoup doc]
  (for [r (.select doc "project > repositories > repository")]
    {:id (text r "id")
     :url (text r "url")}))

(defn artifact-info [^Jsoup doc]
  ;; These `parent` fallbacks are a bit of a hack but
  ;; I didn't want to modify the data model and make this
  ;; leak outside of this namespace - Martin
  {:group-id    (or (text doc "project > groupId")
                    (text doc "project > parent > groupId"))
   :artifact-id (text doc "project > artifactId")
   :version     (or (text doc "project > version")
                    (text doc "project > parent > version"))
   :description (text doc "project > description")
   :url         (text doc "project > url")})

(comment
  (def doc
    (Jsoup/parse (slurp "https://repo.clojars.org/workflo/macros/0.2.63/macros-0.2.63.pom")))

  (def doc
    (parse (slurp "https://search.maven.org/remotecontent?filepath=org/clojure/clojure/1.9.0/clojure-1.9.0.pom")))

  (println (.toString doc))

  (licenses doc)
  (scm-info doc)
  (dependencies doc)
  (artifact-info doc)
  (repositories doc))

