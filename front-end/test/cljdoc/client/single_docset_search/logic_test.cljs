(ns cljdoc.client.single-docset-search.logic-test
  (:require #_{:clj-kondo/ignore [:unused-namespace]} ["assert" :as assert] ;; hack, needed for is
            [cljdoc.client.single-docset-search.logic :as logic])
  (:require-macros
   [cljdoc.client.test-macros :refer [deftest is]]))

(println "=[cljdoc.client.single-docset-search.logic-test]=")

(deftest tokenize-test
  (is (= [] (logic/tokenize "")))
  (is (= [] (logic/tokenize nil)))
  (is (= ["sanity"] (logic/tokenize "sanity")))
  (is (= ["words" "are" "split"] (logic/tokenize "words are split")))
  (is (= ["punctuation" "is" "removed"] (logic/tokenize "punctuation, is removed.")))
  (is (= ["interesting" "chars!" "maybe" "vars?"] (logic/tokenize "interesting chars! maybe vars?")))
  (is (= ["set-posix-file-permissions"] (logic/tokenize "set-posix-file-permissions"))))

(deftest sub-tokenize-test
  (is (= [] (logic/sub-tokenize ["no" "new" "tokens" "from" "simple" "words"])))
  (is (= ["one" "two" "three" "four"] (logic/sub-tokenize ["one/two//three///four"]))))

;; notes:
;; prefers name over doc
;; searches sub-words (not great yet)

(deftest search-test
  (let [ns1 {"id" 0
             "platform" "clj"
             "name" "foo.ns"
             "path" "/d/foo/bar/1.2.3/api/foo.ns"
             "doc" "somefoons foonsclj doc"
             "kind" "namespace"}
        ns2 {"id" 1
             "platform" "cljs"
             "name" "foo.ns"
             "path" "/d/foo/bar/1.2.3/api/foo.ns"
             "doc" "somefoons foonscljs doc"
             "kind" "namespace"}
        var1 {"id" 2
              "platform" "clj"
              "type" "var"
              "namespace" "foo.ns"
              "name" "somevar"
              "arglists" [["somearg" "someotherarg"]]
              "doc" "Returns true if somearg blah blah\n",
              "members" []
              "path" "/d/babashka/fs/0.5.31/api/babashka.fs#absolute?",
              "kind" "def"}
        index (logic/build-search-index
               [ns1 ns2 var1])]
    (is (= [] (logic/search index "wontbefound")))
    (is (= [] (logic/search index "someotherarg")) "arglists not currently indexed")
    (is (= [{:doc var1}] (mapv #(dissoc % "result") (logic/search index "somearg"))) "found in docstring")))
