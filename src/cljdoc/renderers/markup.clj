(ns cljdoc.renderers.markup
  (:import (org.asciidoctor Asciidoctor Asciidoctor$Factory OptionsBuilder Attributes)
           (org.commonmark.parser Parser)
           (org.commonmark.renderer.html HtmlRenderer)))

(def adoc-container
  (Asciidoctor$Factory/create ""))

;; (doto (java.util.HashMap.)
;;   (.put "attributes" (doto (java.util.HashMap.)
;;                        (.put "source-highlighter" "pygments"))))

(defn asciidoc-to-html [file-content]
  (.convert adoc-container file-content {}))

(def md-container
  (.build (Parser/builder)))

(def md-renderer
  (.build (HtmlRenderer/builder)))

(defn markdown-to-html [input-str]
  (->> (.parse md-container input-str)
       (.render md-renderer)))

(comment
  (markdown-to-html "# hello world")

  )

