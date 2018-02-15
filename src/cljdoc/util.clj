(ns cljdoc.util)

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

(defn local-jar-file [coordinate]
  ;; (jar-file '[org.martinklepsch/derivatives "0.2.0"])
  (->> (boot.pod/resolve-dependencies {:dependencies [coordinate]})
       (filter #(= coordinate (:dep %)))
       (first)
       :jar))

(defn remote-jar-file [[project version :as coordinate]]
  ;; Probably doesn't work with SNAPSHOTs
  (str "https://repo.clojars.org/"
       (group-id project) "/"
       (artifact-id project) "/"
       version "/"
       (artifact-id project) "-" version ".jar"))

(defn assert-first [[x & rest :as xs]]
  (if (empty? rest)
    x
    (throw (ex-info "Expected collection with one item, got multiple"
                    {:coll xs}))))

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
