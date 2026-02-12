(ns cljdoc.render.api-searchset-test
  (:require [cljdoc.render.api-searchset :as api-searchset]
            [cljdoc.spec.docset :as cbs]
            [cljdoc.spec.searchset :as ss]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]))

(def docset (-> "resources/test_data/docset.edn"
                slurp
                edn/read-string))

(comment
  ;; run regen-results to regenerate expected results.
  ;; test project docs will need to have been built and server started from cljdoc.server.system
  (require '[clojure.pprint]
           '[cljdoc.spec.util :as util]
           '[clojure.walk :as walk])

  (defn pp-str [f]
    (with-out-str (clojure.pprint/pprint f)))

  (defn sort-k-set [k by-keys m]
    (assoc m k
           (into (sorted-set-by (fn [x y]
                                  (reduce (fn [c k]
                                            (if (not (zero? c))
                                              (reduced c)
                                              (compare (k x) (k y))))
                                          0
                                          by-keys)))
                 (k m))))

  (defn sort-results-form [form]
    (cond->> form
      (set? (:namespaces form)) (sort-k-set :namespaces [:name :platform])
      (set? (:defs form)) (sort-k-set :defs [:namespace :name :platform])
      :always (walk/postwalk (fn [n] (if (map? n)
                                       (into (sorted-map) n)
                                       n)))))

  (defn regen-results []
    (let [docset (util/load-docset "rewrite-clj/rewrite-clj/1.0.767-alpha")
          searchset (api-searchset/docset->searchset docset)]
      (spit "resources/test_data/docset.edn" (pp-str (sort-results-form docset)))
      (spit "resources/test_data/searchset.edn" (pp-str (sort-results-form searchset)))
      ;; make sure you check these to confirm that namespaces + defs + docs are all generating correctly
      ;;
      ;; don't worry about the namespace id, the id comes from an auto-increment column
      ;; in the database and will be different for each system
      ))

  (regen-results)

  :eoc)

(def doc (get-in docset [:version :doc 0]))

(def version-entity (:version-entity docset))

(def searchset (-> "resources/test_data/searchset.edn"
                   slurp
                   edn/read-string))

(t/deftest path-for-doc
  (t/testing "gets the route for a given doc"
    (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/doc/readme"
             (api-searchset/path-for-doc doc version-entity))))
  (t/testing "gets the route if the doc has a slug path instead of a slug"
    (let [attrs (:attrs doc)
          slug-path-attrs (-> attrs (dissoc :slug) (assoc :slug-path ["read" "me"]))
          slug-path-doc (assoc doc :attrs slug-path-attrs)]
      (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/doc/read/me"
               (api-searchset/path-for-doc slug-path-doc version-entity))))))

(t/deftest path-for-namespace
  (t/testing "gets a route for a namespace"
    (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/api/rewrite-clj.node"
             (api-searchset/path-for-namespace version-entity "rewrite-clj.node")))))

(t/deftest path-for-def
  (t/testing "gets a route for a def"
    (t/is (= "/d/rewrite-clj/rewrite-clj/1.0.767-alpha/api/rewrite-clj.node#coerce"
             (api-searchset/path-for-def version-entity "rewrite-clj.node" "coerce")))))

(t/deftest docstring-text-test
  (t/is (= "just the text" (api-searchset/docstring-text "just the text" {:docstring-format :cljdoc/plaintext })))
  (t/is (= "just the text" (api-searchset/docstring-text "# just **the** `text`" {:docstring-format :cljdoc/markdown })))
  (t/is (= (str "some code block "
                "(ns foo.bar.baz) "
                "(defn boop[bap] "
                "(map #(inc %) bap))")
           (str/replace 
             (api-searchset/docstring-text (str "## some code block\n"
                                                "```clojure\n"
                                                "(ns foo.bar.baz)\n"
                                                "(defn boop[bap]\n"
                                                "  (map #(inc %) bap))\n"
                                                "```")
                                           {:docstring-format :cljdoc/markdown })
             #"\s+" " "))))

(t/deftest doc-text-test
  (t/is (match?
         [{:name "doc title1",
           :path "/d/foo/bar/1.2.3/doc/slug#",
           :doc "before heading "}
          {:name "doc title1 - heading 1",
           :path "/d/foo/bar/1.2.3/doc/slug#heading-1",
           :doc "content 1 "}]
         (api-searchset/->docs [{:title "doc title1"
                                 :attrs {:cljdoc.doc/source-file "foo.md"
                                         :cljdoc/markdown "before heading\n# heading 1\ncontent 1"
                                         :cljdoc.doc/type :cljdoc/markdown
                                         :slug "slug"}}]
                               {:group-id "foo" :artifact-id "bar" :version "1.2.3"}))))

(t/deftest docset->searchset
  (let [generated-searchset (api-searchset/docset->searchset docset)]
    (t/testing "input docset is valid"
      (let [explanation (cbs/explain-humanized docset)]
        (t/is (nil? explanation) (format "expected nil for %s" docset))))
    (t/testing "converts a docset into a searchset"
      (t/is (match? (m/equals searchset) generated-searchset)))
    (t/testing "produces a valid searchset"
      (let [explanation (ss/explain-humanized generated-searchset)]
        (t/is (nil? explanation) (format "expected nil for %s" generated-searchset))))))
