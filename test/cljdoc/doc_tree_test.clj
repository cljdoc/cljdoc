(ns cljdoc.doc-tree-test
  (:require [cljdoc.doc-tree :as doctree]
            [clojure.test :as t]
            [clojure.spec.test.alpha :as st]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]))

(t/use-fixtures :once (fn [f] (st/instrument) (f)))

(t/deftest process-toc-test
  (t/is (=
         [{:title "Readme"
           :attrs {:cljdoc.doc/source-file "README.md"
                   :cljdoc.doc/type :cljdoc/markdown
                   :cljdoc.doc/contributors ["A" "B" "C"]
                   :cljdoc/markdown "README.md"
                   :slug "readme"}
           :children [{:title "Nested"
                       :attrs {:cljdoc.doc/source-file "nested.adoc"
                               :cljdoc.doc/type :cljdoc/asciidoc
                               :cljdoc.doc/contributors ["A" "B" "C"]
                               :cljdoc/asciidoc "nested.adoc"
                               :slug "nested"}}]}]
         (doctree/process-toc
          {:slurp-fn identity
           :get-contributors (constantly ["A" "B" "C"])}
          [["Readme" {:file "README.md"}
            ["Nested" {:file "nested.adoc"}]]]))))

(spec/def ::entry
  (spec/keys :req-un [:cljdoc.doc-tree/attrs :cljdoc.doc-tree/title]
             :opt-un [::children]))

(spec/def ::children
  (spec/coll-of ::entry))

(t/deftest find-neighbour-articles
  (let [example-tree (doctree/add-slug-path (gen/sample (spec/gen ::entry) 3))]
    ;; first element in tree will have no previous entry
    (t/is (->> example-tree
               first
               :attrs
               :slug-path
               (doctree/get-neighbour-entries example-tree)
               first
               nil?))
    ;; any non last entry in tree have two neighbour
    (t/is (->> example-tree
               second
               :attrs
               :slug-path
               (doctree/get-neighbour-entries example-tree)
               (every? (complement nil?))))
    ;; last element in three have no next neighbour
    (t/is (->> example-tree
               doctree/flatten*
               reverse
               first
               :attrs
               :slug-path
               (doctree/get-neighbour-entries example-tree)
               last
               nil?))))

(comment
  (def example-tree (doctree/add-slug-path (gen/sample (spec/gen ::entry) 3)))

  (def test-slug-path (->> example-tree
                           doctree/flatten*
                           reverse
                           first
                           :attrs
                           :slug-path))
  test-slug-path
  (count (filter #(-> %
                      :attrs
                      :slug-path
                      (= test-slug-path)) (doctree/flatten* example-tree)))


  (->> example-tree
       second
       :attrs
       :slug-path
       (doctree/get-neighbour-entries example-tree)
       (every? (complement nil?)))

  (->> example-tree
       doctree/flatten*
       reverse
       first
       :attrs
       :slug-path
       (doctree/get-neighbour-entries example-tree)
       last
       nil?))
