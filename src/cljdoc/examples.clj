(ns cljdoc.examples
  (:require [clojure.spec.alpha :as spec]
            [clojure.string :as string]
            [clj-yaml.core :as yaml]))

(spec/def ::type #{:markdown})
(spec/def ::author (spec/keys :req-un [::name ::email]))
(spec/def ::authors (spec/coll-of ::author))
(spec/def ::contents string?)
(spec/def ::ns string?)
(spec/def ::var (spec/nilable string?))
(spec/def ::created inst?)
(spec/def ::example (spec/keys :req [::type ::ns ::var ::authors ::contents ::created]))
(spec/def ::examples (spec/coll-of ::example))

(defmulti process-example :type)

(defmethod process-example :markdown [{:keys [path type content commits]}]
  (let [[_ yaml-lines _ content-lines] (partition-by #(= "---" %) (string/split-lines content))
        yaml-raw (string/join "\n" yaml-lines)
        content-raw (string/join "\n" content-lines)
        {:keys [for-var] :as yaml}
        (if (.startsWith content "---\n")
          (do (assert (seq yaml-lines) "YAML metadata missing")
              (assert (seq content-lines) "No content found in example")
              (yaml/parse-string yaml-raw))
          (throw (ex-info "Example is missing YAML metadata" {})))]
    (assert (.contains for-var "/") "we can enable more general stuff here butneeds more handling")
    {::type type
     ::authors  (set (map #(select-keys % [:name :email]) commits))
     ::contents (string/trim content-raw)
     ::created  (:date (last commits))
     ::ns       (namespace (symbol for-var))
     ::var      (name (symbol for-var))}))

(comment
  (require '[cljdoc.git-repo :as gr]
           '[clojure.java.io :as io])
  (def r (gr/->repo (io/file "/Users/martinklepsch/code/02-oss/amazonica")))

  (gr/find-examples r "with-example")

  (map :date (gr/get-commits r "0.3.7" "project.clj"))

  (spec/conform ::example
               (process-example (first (gr/find-examples r "with-example"))))

  (def exs
    (map process-example (gr/find-examples r "with-example")))

  )
