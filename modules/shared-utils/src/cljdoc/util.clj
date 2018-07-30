(ns cljdoc.util
  "Utility functions :)

  These are available in the analysis environment and thus
  should work without any additional dependencies."
  (:refer-clojure :exclude [time])
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.nio.file Files)))

(def hardcoded-config
  (clojure.edn/read-string (slurp (io/resource "hardcoded-projects-config.edn"))))

(defn group-id [project]
  (or (if (symbol? project)
        (namespace project)
        (namespace (symbol project)))
      (name project)))

(defn artifact-id [project]
  (name (symbol project)))

(defn normalize-project [project]
  (str (group-id project) "/" (artifact-id project)))

(defn codox-edn [project version]
  ;; TODO maybe delete, currently not used (like other codox stuff)
  (str "codox-edn/" project "/" version "/codox.edn"))

(defn cljdoc-edn
  [project version]
  {:pre [(some? project) (string? version)]}
  (str "cljdoc-edn/" (group-id project) "/" (artifact-id project) "/" version "/cljdoc.edn"))

(defn git-dir [project version]
  (str "git-repos/" (group-id project) "/" (artifact-id project) "/" version "/"))

(defn clojars-id [{:keys [group-id artifact-id] :as cache-id}]
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


(defn gh-owner [gh-url]
  (second (re-find #"^https*://github.com/([^\/]+)/" gh-url)))

(defn gh-repo [gh-url]
  (second (re-find #"^https*://github.com/[^\/]+/([^/]+)" gh-url)))

(defn gh-coordinate [gh-url]
  (str (gh-owner gh-url) "/" (gh-repo gh-url)))

(defn github-url [type]
  (let [base "https://github.com/cljdoc/cljdoc"]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :roadmap            (str base "/blob/master/doc/roadmap.adoc")
      :userguide/scm-faq  (str base "/blob/master/doc/userguide/faq.md#how-do-i-set-scm-info-for-my-project")
      :userguide/authors  (str base "/blob/master/doc/userguide/for-library-authors.adoc")
      :userguide/users    (str base "/blob/master/doc/userguide/for-users.md")

      :userguide/basic-setup  (str (github-url :userguide/authors) "#basic-setup")
      :userguide/articles     (str (github-url :userguide/authors) "#articles")
      :userguide/offline-docs (str (github-url :userguide/users) "#offline-docs"))))

(defn strip-common-start-string
  "Remove the common substring from `s2` that both, `s1`
  and `s2` start with."
  [s1 s2]
  (->> (map vector s1 s2)
       (take-while #(= (first %) (second %)))
       (count)
       (subs s2)))

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

(defmacro time
  "Same as `clojure.core/time` but returns measured time (ms) instead of return value of expr."
  {:added "1.0"}
  [expr]
  `(let [start# (. System (nanoTime))]
     ~expr
     (/ (double (- (. System (nanoTime)) start#)) 1000000.0)))
