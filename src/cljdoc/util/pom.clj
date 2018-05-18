(ns cljdoc.util.pom
  (:import (org.jsoup Jsoup)))

(defn parse [pom-str]
  (Jsoup/parse pom-str))

(defn text [^Jsoup doc sel]
  (some-> (.select doc sel)
          (first)
          (.ownText)))

(defn licenses [^Jsoup doc]
  (for [l (.select doc "project > licenses > license")]
    {:name (text l "name")
     :url  (text l "url")}))

(defn scm-info [^Jsoup doc]
  {:url (text doc "project > scm > url")
   :sha (text doc "project > scm > tag")})

(defn dependencies [^Jsoup doc]
  (for [d (.select doc "project > dependencies > dependency")]
    {:group-id    (text d "groupId")
     :artifact-id (text d "artifactId")
     :version     (text d "version")}))

(defn artifact-info [^Jsoup doc]
  {:group-id    (text doc "project > groupId")
   :artifact-id (text doc "project > artifactId")
   :version     (text doc "project > version")
   :description (text doc "project > description")
   :url         (text doc "project > url")})

(comment
  (def doc
    (Jsoup/parse (slurp "https://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.pom")))

  (println (.toString doc))

  (licenses doc)
  (scm-info doc)
  (dependencies doc)
  (artifact-info doc)
  )
