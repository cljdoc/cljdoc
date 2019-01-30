(ns cljdoc.util
  "Utility functions :)

  These are available in the analysis environment and thus should work
  without any additional dependencies or further assumptions about
  what's on the classpath."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk])
  (:import (java.nio.file Files Paths)))

(def hardcoded-config
  ;; NOTE `delay` is used here because the stripped-down analysis env
  ;; doesn't have `hardcoded-projects-config.edn` on the classpath
  ;; TODO move elsewhere
  (delay (edn/read-string (slurp (io/resource "hardcoded-projects-config.edn")))))

(defn group-id [project]
  (or (if (symbol? project)
        (namespace project)
        (namespace (symbol project)))
      (name project)))

(defn artifact-id [project]
  (name (symbol project)))

(defn normalize-project [project]
  (str (group-id project) "/" (artifact-id project)))

(defn version-entity [project version]
  {:group-id (group-id project)
   :artifact-id (artifact-id project)
   :version version})

(defn codox-edn [project version]
  ;; TODO maybe delete, currently not used (like other codox stuff)
  (str "codox-edn/" project "/" version "/codox.edn"))

(def analysis-output-prefix
  "The -main of `cljdoc.analysis.runner` will write files to this directory.

  Be careful when changing it since that path is also hardcoded in the
  [cljdoc-builder](https://github.com/martinklepsch/cljdoc-builder)
  CircleCI configuration"
  ;; ---
  "/tmp/cljdoc/analysis-out/")

(defn cljdoc-edn
  [project version]
  {:pre [(some? project) (string? version)]}
  (str "cljdoc-edn/" (group-id project) "/" (artifact-id project) "/" version "/cljdoc.edn"))

(defn serialize-cljdoc-edn [analyze-result]
  ;; the analyzed structure can contain regex #"..." (e.g. in :arglists)
  ;; and they can't be read in again using edn/read-string
  ;; so there are changed to #regex"..." and read in with a custom reader
  (->> analyze-result
       (walk/postwalk #(if (instance? java.util.regex.Pattern %)
                         (tagged-literal 'regex (str %))
                         %))
       (pr-str)))

(defn read-cljdoc-edn
  [file]
  {:pre [(some? file)]}
  (edn/read-string {:readers {'regex re-pattern}} (slurp file)))

(defn git-dir [project version]
  (str "git-repos/" (group-id project) "/" (artifact-id project) "/" version "/"))

(defn clojars-id [{:keys [group-id artifact-id] :as artifact-entity}]
  (if (= group-id artifact-id)
    artifact-id
    (str group-id "/" artifact-id)))

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

(defn normalize-git-url
  "Ensure that the passed string is a git URL and that it's using HTTPS"
  [s]
  (cond-> s
    (.startsWith s "http") (string/replace #"^http://" "https://")
    (.startsWith s "git@github.com:") (string/replace #"^git@github.com:" "https://github.com/")
    (.endsWith s ".git") (string/replace #".git$" "")))

(defn gh-url? [s]
  (some-> s (.contains "github.com")))

(defn version-tag? [pom-version tag]
  (or (= pom-version tag)
      (= (str "v" pom-version) tag)))

(defn infer-platforms-from-src-dir
  "Given a directory `src-dir` inspect all files and infer which
  platforms the source files likely target."
  [^java.io.File src-dir]
  (assert (< 1 (count (file-seq src-dir))) "jar contents dir does not contain any files")
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

(defn delete-directory! [dir]
  (let [{:keys [files dirs]} (group-by (fn [f]
                                         (cond (.isDirectory f) :dirs
                                               (.isFile f) :files))
                                       (file-seq dir))]
    (doseq [f files] (.delete f))
    (doseq [d (reverse dirs)] (.delete d))))

(defn system-temp-dir [prefix]
  (.toFile (Files/createTempDirectory
            (clojure.string/replace prefix #"/" "-")
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn system-temp-file [prefix suffix]
  (.toFile (Files/createTempFile
            (clojure.string/replace prefix #"/" "-")
            suffix
            (into-array java.nio.file.attribute.FileAttribute []))))

;; (defn assert-match [project version artifact-enitity]
;;   (assert (and (:group-id artifact-entity)
;;                (:artifact-id artifact-entity)
;;                (:version artifact-entity))
;;           (format "Malformed artifact entity %s" artifact-entity))
;;   (when-not (and (= (group-id project) (:group-id artifact-entity))
;;                  (= (artifact-id project) (:artifact-id artifact-entity)))
;;     (throw (Exception. (format "Mismatch between project and pom-info: %s<>%s"
;;                                (normalize-project project)
;;                                (normalize-project (-> cljdoc-edn :pom :project))))))
;;   (when-not (= version (-> cljdoc-edn :pom :version))
;;     (throw (Exception. (format "Mismatch between version and pom-info: %s<>%s"
;;                                version
;;                                (-> cljdoc-edn :pom :version))))))

(defn github-url [type]
  (let [base "https://github.com/cljdoc/cljdoc"]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :roadmap            (str base "/blob/master/doc/roadmap.adoc")
      :running-locally    (str base "/blob/master/doc/running-cljdoc-locally.md")
      :userguide/scm-faq  (str base "/blob/master/doc/userguide/faq.md#how-do-i-set-scm-info-for-my-project")
      :userguide/authors  (str base "/blob/master/doc/userguide/for-library-authors.adoc")
      :userguide/users    (str base "/blob/master/doc/userguide/for-users.md")

      :userguide/basic-setup  (str (github-url :userguide/authors) "#basic-setup")
      :userguide/articles     (str (github-url :userguide/authors) "#articles")
      :userguide/offline-docs (str (github-url :userguide/users) "#offline-docs"))))

(defn relativize-path
  "Remove the segments at the beginning of a path `s2` that are identical
  to the beginning segments of `s1`. This is useful when wanting to render
  relative links instead of absolute ones.

  Example:

  ```
  (relativize-path \"doc/common-abc.html\" \"doc/common-xyz.html\")
  ;; => \"common-xyz.html\"
  ```"
  [s1 s2]
  (let [->path #(Paths/get % (make-array String 0))
        p1 (->path s1)
        p2 (->path s2)
        relative (.relativize p1 p2)]
    ;; Not entirely sure why `relativize` returns a path with
    ;; this extra nesting but we just use `subpath` to get rid of it
    ;; This extra nesting isn't present if one path is contained in the other
    (if (or (.startsWith p1 p2)
            (.startsWith p2 p1))
      (str relative)
      (str (.subpath relative 1 (.getNameCount relative))))))

(defn uri-path
  "Return path part of a URL, this is probably part of pedestal in
  some way but I couldn't find it fast enough. TODO replace."
  [uri]
  (-> uri
      (string/replace #"^https*://" "")
      (string/replace #"^[^/]*" "")))

(defn replant-ns
  "Given a fully-qualified `base` and a potentially relative `target` namespace,
  return the fully qualified version of `target`. Assumes that all `target` namespaces
  with identical first segments to `base` are already absolute."
  [base target]
  (if (= (first (string/split base #"\."))
         (first (string/split target #"\.")))
    target
    (string/join "."
                 (-> (string/split base #"\.")
                     drop-last
                     vec
                     (conj target)))))

(defn mean [coll]
  (if (seq coll) (/ (reduce + coll) (count coll)) 0))

(defn variance
  "Returns the variance for a collection of values."
  ;; we should probably just use some libarry for this...
  [coll]
  (when (seq coll)
    (let [sqr  (fn sqr [x] (* x x))
          avg  (mean coll)]
      (mean (map #(sqr (- % avg)) coll)))))
