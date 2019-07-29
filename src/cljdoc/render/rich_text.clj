(ns cljdoc.render.rich-text
  (:require [clojure.string :as string])
  (:import (org.asciidoctor Asciidoctor$Factory Options)
           (com.vladsch.flexmark.parser Parser)
           (com.vladsch.flexmark.html HtmlRenderer LinkResolverFactory LinkResolver CustomNodeRenderer)
           (com.vladsch.flexmark.html.renderer ResolvedLink LinkType LinkStatus LinkResolverContext DelegatingNodeRendererFactory NodeRenderer NodeRenderingHandler)
           (com.vladsch.flexmark.ext.gfm.tables TablesExtension)
           (com.vladsch.flexmark.ext.autolink AutolinkExtension)
           (com.vladsch.flexmark.ext.anchorlink AnchorLinkExtension)
           (com.vladsch.flexmark.ext.wikilink WikiLinkExtension WikiLink internal.WikiLinkNodeRenderer$Factory)
           (com.vladsch.flexmark.util.data MutableDataSet DataHolder)))

(def adoc-container
  (Asciidoctor$Factory/create ""))

(defn asciidoc-to-html [file-content]
  (let [opts (doto (Options.)
               (.setAttributes (java.util.HashMap. {"env-cljdoc" true})))]
    (.convert adoc-container file-content opts)))

(def md-extensions
  [(TablesExtension/create)
   (AutolinkExtension/create)
   (AnchorLinkExtension/create)
   (WikiLinkExtension/create)])

(def md-container
  (.. (Parser/builder)
      (extensions md-extensions)
      (build)))


(defn- md-renderer
  "Create a Markdown renderer."
  [{:keys [escape-html?
           render-wiki-link]
    :as _opts}]
  (.. (HtmlRenderer/builder
       (doto (MutableDataSet.)
         (.set AnchorLinkExtension/ANCHORLINKS_ANCHOR_CLASS "md-anchor")
         (.set HtmlRenderer/FENCED_CODE_NO_LANGUAGE_CLASS "language-clojure")))
      (escapeHtml (boolean escape-html?))
      ;; Resolve wikilinks
      (linkResolverFactory
        (reify LinkResolverFactory
          (getAfterDependents [_this] nil)
          (getBeforeDependents [_this] nil)
          (affectsGlobalScope [_this] false)
          (^LinkResolver apply [_this ^LinkResolverContext _ctx]
            (reify LinkResolver
              (resolveLink [_this _node _ctx link]
                (if (= (.getLinkType link) WikiLinkExtension/WIKI_LINK)
                  (ResolvedLink. LinkType/LINK
                                 ((or render-wiki-link identity) (.getUrl link))
                                 nil
                                 LinkStatus/UNCHECKED)
                  link))))))
      ;; Wrap wikilinks content in <code>
      (nodeRendererFactory
       (reify DelegatingNodeRendererFactory
         (getDelegates [_this]
           #{WikiLinkNodeRenderer$Factory})
         (^NodeRenderer apply [_this ^DataHolder _options]
          (reify NodeRenderer
            (getNodeRenderingHandlers [_this]
              #{(NodeRenderingHandler.
                 WikiLink
                 (reify CustomNodeRenderer
                   (render [_this node ctx html]
                     (let [resolved-link (.resolveLink ctx WikiLinkExtension/WIKI_LINK (.. node getLink unescape) nil)
                           url (.getUrl resolved-link)]
                       (.raw html (str "<a href=\"" url "\"><code>" (.. node getLink) "</code></a>"))))))})))))
      (extensions md-extensions)
      (build)))

(defn markdown-to-html
  "Parse the given string as Markdown and return HTML.

  A second argument can be passed to customize the rendring
  supported options:

  - `:escape-html?` if true HTML in input-str will be escaped"
  ([input-str]
   (markdown-to-html input-str {}))
  ([input-str opts]
   (->> (.parse md-container input-str)
        (.render (md-renderer opts)))))

(defmulti render-text
  "An extension point for the rendering of different article types.

  `type` is determined by [[cljdoc.doc-tree/filepath->type]]."
  (fn [[type contents]]
    type))

(defmethod render-text :cljdoc/markdown [[_ content]]
  (markdown-to-html content))

(defmethod render-text :cljdoc/asciidoc [[_ content]]
  (asciidoc-to-html content))

(comment
  (markdown-to-html "*hello world* <code>x</code>")

  (markdown-to-html "*hello world* <code>x</code>" {:escape-html? true})

  (markdown-to-html "*hello world* [[link]]" {:escape-html? true
                                              :render-wiki-link (constantly "???")})

  (asciidoc-to-html "ifdef::env-cljdoc[]\nCLJDOC\nendif::[]\nifndef::env-cljdoc[]\nNOT_CLJDOC\nendif::[]")

  )

