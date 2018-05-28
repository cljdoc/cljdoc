(ns cljdoc.util
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as string]))

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

(defn ensure-https
  "Ensure that the passed string is a URL and that it's using HTTPS"
  [s]
  (when (.startsWith s "http")
    (clojure.string/replace s #"^http://" "https://")))

(defn gh-url? [s]
  (some-> s (.contains "github.com")))

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

(defn delete-directory [dir]
  (let [{:keys [files dirs]} (group-by (fn [f]
                                         (cond (.isDirectory f) :dirs
                                               (.isFile f) :files))
                                       (file-seq dir))]
    (doseq [f files] (.delete f))
    (doseq [d (reverse dirs)] (.delete d))))

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
  (let [base "https://github.com/martinklepsch/cljdoc"]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :userguide/articles (str base "/blob/master/doc/userguide/articles.md")
      :userguide/scm-faq  (str base "/blob/master/doc/userguide/faq.md#how-do-i-set-scm-info-for-my-project"))))
