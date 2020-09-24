(ns cljdoc.render.rich-text-test
  (:require [cljdoc.render.rich-text :as rich-text]
           [clojure.test :as t]))

(t/deftest renders-wikilinks-from-markdown
  (t/is (= "<p><a href=\"updated:my.namespace.here/fn1\" data-source=\"wikilink\"><code>my.namespace.here/fn1</code></a></p>\n"
           (rich-text/markdown-to-html "[[my.namespace.here/fn1]]"
                                       {:render-wiki-link (fn [ref] (str "updated:" ref))}))))
