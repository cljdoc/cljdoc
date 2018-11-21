;; A minimal zero dependencies script to check if a project
;; directory has any cljdoc specific issues.

;; In particular this checks if the doctree specified in
;; `doc/cljdoc.edn` or `docs/cljdoc.edn` matches the spec
;; and doesn't contain any non-existant files.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.spec.alpha :as spec])

(spec/def ::title string?)
(spec/def ::file string?)

(spec/def ::hiccup-attrs
  (spec/keys :opt-un [::file]))

(spec/def ::hiccup-entry
  (spec/spec
   (spec/cat :title ::title
             :attrs ::hiccup-attrs
             :children (spec/* ::hiccup-entry))))

;; Validation -------------------------------------------------------------------

(defn all-files [toc]
  (->> (tree-seq coll? seq toc)
       (keep :file)))

(defn files-present? [base-dir toc]
  (let [missing (remove #(.exists (io/file base-dir %))
                        (all-files toc))]
    (doseq [m missing]
      (println m "is missing"))
    (not (seq missing))))

(defn -main []
  (let [doc-cljdoc-edn (io/file "doc/cljdoc.edn")
        docs-cljdoc-edn (io/file "docs/cljdoc.edn")
        config-edn (cond
                     (.exists doc-cljdoc-edn)  doc-cljdoc-edn
                     (.exists docs-cljdoc-edn) docs-cljdoc-edn)
        doctree (some-> config-edn slurp edn/read-string :cljdoc.doc/tree)]
    (if doctree
      (System/exit
       (+ (if (files-present? "." doctree) 0 1)
          (if (spec/valid? (spec/coll-of ::hiccup-entry) doctree)
            0
            (do
              (println "Problems were found with the value of :cljdoc.doc/tree in" (.getName config-edn))
              (println (spec/explain-str (spec/coll-of ::hiccup-entry) doctree))
              1))))
      (println "No doctree found"))))

(-main)
