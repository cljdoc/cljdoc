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
    (Jsoup/parse (slurp "https://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.pom")))

  (def doc
    (parse (slurp "https://search.maven.org/remotecontent?filepath=org/clojure/clojure/1.9.0/clojure-1.9.0.pom")))

  (println (.toString doc))

  (licenses doc)
  (scm-info doc)
  (dependencies doc)
  (artifact-info doc)
  )
