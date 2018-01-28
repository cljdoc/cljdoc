(ns cljdoc.renderers.markup
  (:require [clojure.string :as string])
  (:import (org.asciidoctor Asciidoctor Asciidoctor$Factory OptionsBuilder Attributes)
           (org.commonmark.node Image Link)
           (org.commonmark.parser Parser)
           (org.commonmark.renderer.html HtmlRenderer AttributeProvider AttributeProviderFactory)
           (org.commonmark.ext.gfm.tables TablesExtension)
           (org.commonmark.ext.heading.anchor HeadingAnchorExtension)))

(def adoc-container
  (Asciidoctor$Factory/create ""))

;; (doto (java.util.HashMap.)
;;   (.put "attributes" (doto (java.util.HashMap.)
;;                        (.put "source-highlighter" "pygments"))))

(defn asciidoc-to-html [file-content]
  (.convert adoc-container file-content {}))

(def md-extensions
  [(TablesExtension/create)
   (HeadingAnchorExtension/create)])

(def md-container
  (.. (Parser/builder)
      (extensions md-extensions)
      (build)))

(defn- absolute-uri? [s]
  (or (.startsWith s "https://")
      (.startsWith s "http://")
      (.startsWith s "//")))

;; TODO implement these as xml.zipper/hiccup walkers

#_(defn ->ImageUrlFixer
  [ctx]
  ;; TODO assertion that (:scm ctx) contains proper scm info
  (proxy [AttributeProviderFactory] []
    (create [_]
      (reify AttributeProvider
        (setAttributes [_ node tag-name attrs]
          (when (and (instance? Image node)
                     (not (absolute-uri? (get attrs "src"))))
            (.put attrs "src"
                  (str (-> ctx :scm :url)
                       "/raw/" (-> ctx :scm :commit)
                       "/" (get attrs "src")))))))))

#_(defn ->LinkUrlFixer
  [{:keys [uri-mapping]}]
  (proxy [AttributeProviderFactory] []
    (create [_]
      (reify AttributeProvider
        (setAttributes [_ node tag-name attrs]
          (when (and (instance? Link node)
                     (not (absolute-uri? (get attrs "href"))))
            (if-let [corrected (get uri-mapping (string/replace (get attrs "href") #"^/" ""))]
              (.put attrs "href" corrected)
              (println "Could not fix link for uri" (get attrs "href")))))))))


(defn md-renderer
  "Create a Markdown renderer."
  []
  (.. (HtmlRenderer/builder)
      (extensions md-extensions)
      (build)))

(defn markdown-to-html [input-str]
  (->> (.parse md-container input-str)
       (.render (md-renderer))))

(comment
  (markdown-to-html "# hello world")

  )

