(ns cljdoc.render.emoji
  "Render github emojis from their :tags:"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as hiccup])
  (:import (org.jsoup Jsoup)
           (org.jsoup.select NodeTraversor NodeVisitor)
           (org.jsoup.nodes Document Node TextNode)))

(set! *warn-on-reflection* true)

(def ^:private emojis (-> (io/resource "emojis.edn")
                          slurp
                          edn/read-string))

(defn- parse-html ^Document [^String html-str]
  (let [doc (Jsoup/parse html-str)
        props (.outputSettings doc)]
    (.prettyPrint props false)
    doc))

(def ^:private skip-tags
  ;; likely overkill in number of tags, but harmless
  #{"pre" "code" "tt" "samp" "kbd" "var" 
    "script" "style" "math" "mrow" "mi" "mo" "msup" "msub"})

;;  could be smarter?
(defn- has-skip-ancestor? [^Node node]
  (loop [current (.parent node)]
    (cond
      (nil? current) false
      (skip-tags (.nodeName current)) true
      :else (recur (.parent current)))))

(defn- render-emoji-alias
  "Return HTML representing emoji alias(es) in text"
  [text] ^String
  (str/replace text #":([a-z0-9_+-]+):"
               (fn [[full-match alias]]
                 (let [{:keys [type img-url unicode]} (get emojis alias)]
                   (case type
                     :unicode unicode
                     :image 
                     (str (hiccup/html [:img {:href img-url
                                              :style "width:1rem;height:1rem;vertical-align:middle;"}]))
                     full-match)))))

(defn apply-emojis [html-str]
  (let [doc (parse-html html-str)]
    (NodeTraversor/traverse
      (reify NodeVisitor
        (head [_ node _depth]
          (when (instance? TextNode node)
            (let [text-node ^TextNode node]
              (when-not (has-skip-ancestor? text-node)
                
                (let [original-content (.text text-node)
                      new-content (render-emoji-alias original-content)]
                  (when (not= original-content new-content)
                    (.before node new-content)
                    (.remove node)))))))

        (tail [_ _node _depth] nil))
      doc)
    (-> doc .body .html .toString)))

(comment
  (apply-emojis "<div>:fire: :nope: :heart:</div>")
  ;; => "<div>🔥 :nope: ❤️</div>"
  (apply-emojis "<div>:o2: <code>:o2:</code></div>")
  ;; => "<div>🅾️ <code>:o2:</code></div>"
  (apply-emojis ":fire: :octocat: :dog:")
  ;; => "🔥 <img href=\"https://github.githubassets.com/images/icons/emoji/octocat.png?v8\" style=\"width:1rem;height:1rem;vertical-align:middle;\"> 🐶"

  (str (hiccup/html [:img]))

  (Jsoup/parseBodyFragment "🔥")
  
  (-> (Jsoup/parseBodyFragment "boo 🔥 <img href=\"foo\"/>") .body .childNodes)
  
  )
