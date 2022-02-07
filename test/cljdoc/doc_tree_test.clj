(ns cljdoc.doc-tree-test
  (:require [cljdoc.doc-tree :as doctree]
            [clojure.test :as t]
            [clojure.test.check.clojure-test :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.spec.test.alpha :as st]
            [clojure.spec.alpha :as spec]))

(t/use-fixtures :once (fn [f] (st/instrument) (f)))

(t/deftest process-toc-test
  (t/is (=
         [{:title "Readme",
           :attrs
           {:cljdoc.doc/source-file "README.md",
            :cljdoc/markdown "README.md",
            :cljdoc.doc/type :cljdoc/markdown,
            :slug "readme",
            :cljdoc.doc/contributors ["A" "B" "C"]},
           :children
           [{:title "Nested",
             :attrs
             {:cljdoc.doc/source-file "nested.adoc",
              :cljdoc/asciidoc "nested.adoc",
              :cljdoc.doc/type :cljdoc/asciidoc,
              :slug "nested",
              :cljdoc.doc/contributors ["A" "B" "C"]}}]}]
         (doctree/process-toc
          {:slurp-fn identity
           :get-contributors (constantly ["A" "B" "C"])}
          [["Readme" {:file "README.md"}
            ["Nested" {:file "nested.adoc"}]]]))))

(t/deftest process-links-test
  (t/is (=
         [{:title "Community-Page",
           :link-attrs #:cljdoc.doc{:external-url "http://my-community.com"}}
          {:title "Example-Page",
           :link-attrs #:cljdoc.doc{:external-url "http://example-page"},
           :link-children
           [{:title "Playgound1",
             :link-attrs #:cljdoc.doc{:external-url "http://example-page/playground1"}}
            {:title "Playgound2",
             :link-attrs
             #:cljdoc.doc{:external-url "http://example-page/playground2"}}]}]
         (doctree/process-links
          [["Community-Page" {:url "http://my-community.com"}]
           ["Example-Page" {:url "http://example-page"}
            ["Playgound1" {:url "http://example-page/playground1"}]
            ["Playgound2" {:url "http://example-page/playground2"}]]]))))

;; we redefine spec for entry because for test we want every entry have attrs with slug so that slug-path will be
;; generated (neighbours are found based on the slug-path)
(spec/def ::entry
  (spec/keys :req-un [:cljdoc.doc-tree/attrs :cljdoc.doc-tree/title]
             :opt-un [::children]))

;; encourage generation of unique slugs across siblings.
;; this matches reality and avoids duplicate slug-paths
(defn- slug-is-unique? [entries]
  (if (empty? entries)
    true
    (apply distinct? (map #(get-in % [:attrs :slug]) entries))))

(spec/def ::children
  (spec/and (spec/coll-of ::entry) slug-is-unique?))

(defn- gen-entries []
  (gen/such-that slug-is-unique? (gen/vector (spec/gen ::entry) 3)))

(defn- get-slug-paths [doc-tree]
  (->> doc-tree
       (mapcat #(tree-seq :attrs :children %))
       (map #(get-in % [:attrs :slug-path]))))

(tc/defspec first-element-in-tree-has-no-previous-entry
  1
  (prop/for-all [entries (gen-entries)]
    (let [example-tree (doctree/add-slug-path entries)
          slug-paths (get-slug-paths example-tree)
          [prev-elem elem next-elem] (->> (first slug-paths)
                                          (doctree/get-neighbour-entries example-tree))]
      (and (nil? prev-elem) elem next-elem))))

(tc/defspec inside-elements-in-tree-have-both-next-and-prev
  1
  (prop/for-all [entries (gen-entries)]
    (let [example-tree (doctree/add-slug-path entries)
          slug-paths (get-slug-paths example-tree)]
      (every? (fn [[prev-elem elem next-elem]]
                (and prev-elem elem next-elem))
              (->> slug-paths
                   rest
                   butlast
                   (take 20) ;; generated tree can be large, don't need to test it all
                   (map #(doctree/get-neighbour-entries example-tree %)))))))

(tc/defspec last-element-in-tree-has-no-next-entry
  1
  (prop/for-all [entries (gen-entries)]
    (let [example-tree (doctree/add-slug-path entries)
          slug-paths (get-slug-paths example-tree)
          [prev-elem elem next-elem] (->> (last slug-paths)
                                          (doctree/get-neighbour-entries example-tree))]
      (and prev-elem elem (nil? next-elem)))))

(comment

  (def example-tree (doctree/add-slug-path (gen/sample (spec/gen ::entry) 3)))

  (count (gen/sample (spec/gen ::entry) 3)))
