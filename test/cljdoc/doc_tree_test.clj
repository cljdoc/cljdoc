(ns cljdoc.doc-tree-test
  (:require [cljdoc.doc-tree :as doctree]
            [clojure.test :as t]
            [clojure.spec.test.alpha :as st]))

(t/use-fixtures :once (fn [f] (st/instrument) (f)))

(t/deftest process-toc-test
  (t/is (=
         [{:title "Readme"
           :attrs {:cljdoc.doc/source-file "README.md",
                   :cljdoc.doc/type :cljdoc/markdown
                   :cljdoc.doc/contributors ["A" "B" "C"]
                   :cljdoc/markdown "README.md",
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
