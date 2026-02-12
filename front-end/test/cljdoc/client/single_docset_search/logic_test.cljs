(ns cljdoc.client.single-docset-search.logic-test
  "Pretty hacky test framework. Run via bb test-js."
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
  (is (= ["set-posix-file-permissions"] (logic/tokenize "set-posix-file-permissions")))
  (is (= [":keyword"] (logic/tokenize ":keyword"))))

(deftest sub-tokenize-test
  (is (= [] (logic/sub-tokenize ["no" "new" "tokens" "from" "simple" "words"])))
  (is (= ["one" "two" "three" "four"] (logic/sub-tokenize ["one/two//three///four"])))
  (is (= ["bingo" "bango"] (logic/sub-tokenize ["bingo-bango"]))))

(deftest tokenizer-test
  (is (= ["bingo-bango" "bingo" "bango"] (logic/tokenizer "bingo-bango")))
  (is (= ["returns" "true" "if" "somearg" "bingo-bango" ":keyword" "v1" "v2" "v3"
          "bingo" "bango" "keyword"]
         (logic/tokenizer "Returns true if somearg (bingo-bango :keyword [[v1 v2] v3])\n"))))

(deftest search-test
  (let [ns1 {"id" 0
             "platform" "clj"
             "name" "foo.bar.ns"
             "path" "/d/foo/bar/1.2.3/api/foo.ns"
             "doc" "somefoons foonsclj doc"
             "kind" "namespace"}
        ns2 {"id" 1
             "platform" "cljs"
             "name" "foo.bar.ns"
             "path" "/d/foo/bar/1.2.3/api/foo.ns"
             "doc" "somefoons foonscljs doc"
             "kind" "namespace"}
        var1 {"id" 2
              "platform" "clj"
              "type" "var"
              "namespace" "foo.ns"
              "name" "somevar"
              "arglists" [["somearg" "someotherarg"]]
              "doc" "BLIP.BLAP.FLAP Returns true if somearg (bingo-bango-bongo :keyword :namespaced/kw [v1 v2])\n",
              "members" []
              "path" "/d/babashka/fs/0.5.31/api/babashka.fs#absolute?",
              "kind" "def"}
        doc1 {"id" 3
              "name" "section word1"
              "doc" "section word2"}
        doc2 {"id" 4
              "name" "section word2"
              "doc" "section word1"}
        index (logic/build-search-index
               [ns1 ns2 var1 doc1 doc2])]
    (is (= [] (logic/search index "wontbefound")))
    (is (= [] (logic/search index "someotherarg")) "arglists not currently indexed")
    (is (= [var1]
           (mapv :doc (logic/search index "somearg")))
        "found in docstring")
    (is (= [var1] (mapv :doc (logic/search index "bingo-bango-bongo")))
        "match in fn call position")
    (is (= [var1] (mapv :doc (logic/search index "bingo-bango")))
        "match compound word at start of compound word")
    (is (= [] (mapv :doc (logic/search index "bango-bongo")))
        "does not match compound word at end compound word (which seems ok for now)")
    (is (= [var1] (mapv :doc (logic/search index "bang")))
        "match start of word within compound word")
    (is (= [var1] (mapv :doc (logic/search index "bingo")))
        "match partial compound word in fn call position")
    (is (= [var1] (mapv :doc (logic/search index "bango")))
        "match eo compound word in fn call position")
    (is (= [var1] (mapv :doc (logic/search index "blip.blap.flap")))
        "match dot-separated compound word")
    (is (= [var1] (mapv :doc (logic/search index "blap")))
        "match dot-separated compound subword")
    (is (= [var1] (mapv :doc (logic/search index ":keyword")))
        "match by keyword")
    (is (= [var1] (mapv :doc (logic/search index ":namespaced/kw")))
        "match by namespaced keyword")
    (is (= [var1] (mapv :doc (logic/search index "namespaced")))
        "match by namespaced keyword qualifier")
    (is (= [var1] (mapv :doc (logic/search index "kw")))
        "match by namespaced keyword unqualified")
    (is (= [var1] (mapv :doc (logic/search index "keyword")))
        "match by keyword without colon")
    (let [res (logic/search index "word1")]
      (is (= [doc1 doc2]
             (mapv :doc res))
          "sorts name over doc")
      (is (apply > (mapv #(get-in % [:result :score]) res))
          "weights name over doc"))))
