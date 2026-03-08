(ns cljdoc.test-util.html
  (:require [hickory.core :as hickory])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes TextNode)
           (org.jsoup.select NodeTraversor NodeVisitor)))

(defn ->hiccup
  "Nice to verify in hiccup rather than html"
  [^String html]
  (let [doc (Jsoup/parse html)]
    (-> (.outputSettings doc)
        (.prettyPrint false))
    ;; turf extra newlines, to avoid having to deal with them test expectations
    (NodeTraversor/traverse
     (reify NodeVisitor
       (head [_ node _depth]
         (when (and (instance? TextNode node)
                    (re-matches #"\s*" (.getWholeText ^TextNode node)))
           (.remove node)))
       (tail [_ _node _depth] nil))
     doc)
    (->> doc
         .body
         .toString
         (hickory/parse-fragment)
         (map hickory/as-hiccup))))
