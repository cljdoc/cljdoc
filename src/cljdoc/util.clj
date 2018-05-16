(ns cljdoc.util
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(defn group-id [project]
  (or (if (symbol? project)
        (namespace project)
        (namespace (symbol project)))
      (name project)))

(defn artifact-id [project]
  (name (symbol project)))

(defn codox-edn [project version]
  ;; TODO maybe delete, currently not used (like other codox stuff)
  (str "codox-edn/" project "/" version "/codox.edn"))

(defn cljdoc-edn
  [project version]
  {:pre [(some? project) (string? version)]}
  (str "cljdoc-edn/" (group-id project) "/" (artifact-id project) "/" version "/cljdoc.edn"))

(defn git-dir [project version]
  (str "git-repos/" (group-id project) "/" (artifact-id project) "/" version "/"))

(defn local-jar-file [[project version :as coordinate]]
  ;; (jar-file '[org.martinklepsch/derivatives "0.2.0"])
  (->> (boot.pod/resolve-dependencies {:dependencies [coordinate]})
       (filter #(if (.endsWith version "-SNAPSHOT")
                  (= project (first (:dep %)))
                  (= coordinate (:dep %))))
       (first)
       :jar))

(defn remote-jar-file
  [[project version :as coordinate]]
  {:pre [(some? project) (some? version)]}
  ;; Probably doesn't work with SNAPSHOTs
  (str "https://repo.clojars.org/"
       (string/replace (group-id project) #"\." "/") "/"
       (artifact-id project) "/"
       version "/"
       (artifact-id project) "-" version ".jar"))

(defn assert-first [[x & rest :as xs]]
  (if (empty? rest)
    x
    (throw (ex-info "Expected collection with one item, got multiple"
                    {:coll xs}))))

(def scm-fallback
  {"yada/yada" "https://github.com/juxt/yada/"})

(defn scm-url [pom-map]
  (some->
   (cond (some-> pom-map :scm :url (.contains "github"))
         (:url (:scm pom-map))
         (some-> pom-map :url (.contains "github"))
         (:url pom-map))
   (clojure.string/replace #"^http://" "https://"))) ;; TODO HACK

(defn version-tag? [pom-version tag]
  (or (= pom-version tag)
      (= (str "v" pom-version) tag)))

(defn infer-platforms-from-src-dir
  "Given a directory `src-dir` inspect all files and infer which
  platforms the source files likely target."
  [^java.io.File src-dir]
  (let [file-types (->> (file-seq src-dir)
                        (keep (fn [f]
                                (cond
                                  (.endsWith (.getPath f) ".clj")  :clj
                                  (.endsWith (.getPath f) ".cljs") :cljs
                                  (.endsWith (.getPath f) ".cljc") :cljc))))]
    (case (set file-types)
      #{:clj}  ["clj"]
      #{:cljs} ["cljs"]
      ["clj" "cljs"])))

(defn pom-path [project]
  (format "META-INF/maven/%s/%s/pom.xml"
          (group-id project)
          (artifact-id project)))

(defn find-pom-in-dir [dir project]
  (io/file dir (pom-path project)))
