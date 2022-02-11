(ns cljdoc.render.rich-text
  (:require [cljdoc.render.sanitize :as sanitize])
  (:import (org.asciidoctor Asciidoctor Asciidoctor$Factory Options)
           (com.vladsch.flexmark.parser Parser)
           (com.vladsch.flexmark.html HtmlRenderer LinkResolverFactory LinkResolver)
           (com.vladsch.flexmark.html.renderer ResolvedLink LinkType LinkStatus LinkResolverBasicContext DelegatingNodeRendererFactory NodeRenderer NodeRenderingHandler NodeRenderingHandler$CustomNodeRenderer)
           (com.vladsch.flexmark.ext.tables TablesExtension)
           (com.vladsch.flexmark.ext.autolink AutolinkExtension)
           (com.vladsch.flexmark.ext.anchorlink AnchorLinkExtension)
           (com.vladsch.flexmark.ext.wikilink WikiLinkExtension WikiLink)
           (com.vladsch.flexmark.ext.wikilink.internal WikiLinkNodeRenderer$Factory)
           (com.vladsch.flexmark.util.data MutableDataSet DataHolder)))

(def ^Asciidoctor adoc-container
  (Asciidoctor$Factory/create))

(defn asciidoc-to-html [^String file-content]
  (let [opts (doto (Options.)
               (.setAttributes (java.util.HashMap. {"env-cljdoc" true
                                                    "outfilesuffix" ".adoc"
                                                    "showtitle" true})))]
    (-> (.convert adoc-container file-content opts)
        sanitize/clean)))

(def md-extensions
  [(TablesExtension/create)
   (AutolinkExtension/create)
   (AnchorLinkExtension/create)
   (WikiLinkExtension/create)])

(def ^Parser md-container
  (.. (Parser/builder
       (doto (MutableDataSet.)
         ;; Conform to GitHub tables
         ;; https://github.com/vsch/flexmark-java/issues/370#issuecomment-590074667
         (.set TablesExtension/COLUMN_SPANS false)
         (.set TablesExtension/APPEND_MISSING_COLUMNS true)
         (.set TablesExtension/DISCARD_EXTRA_COLUMNS true)
         (.set TablesExtension/HEADER_SEPARATOR_COLUMN_MATCH true)
         ;; and I think these are needed too:
         ;; https://github.com/vsch/flexmark-java/issues/370#issuecomment-1033215255
         (.set TablesExtension/WITH_CAPTION false)
         (.set TablesExtension/MIN_HEADER_ROWS (int 1))
         (.set TablesExtension/MAX_HEADER_ROWS (int 1))
         (.toImmutable)))
      (extensions md-extensions)
      (build)))

(defn- md-renderer
  "Create a Markdown renderer."
  ^HtmlRenderer [{:keys [escape-html? render-wiki-link]
                  :as _opts}]
  (.. (HtmlRenderer/builder
       (doto (MutableDataSet.)
         (.set AnchorLinkExtension/ANCHORLINKS_ANCHOR_CLASS "md-anchor")
         (.set HtmlRenderer/FENCED_CODE_NO_LANGUAGE_CLASS "language-clojure")
         (.toImmutable)))
      (escapeHtml (boolean escape-html?))
      ;; Resolve wikilinks
      (linkResolverFactory
       (reify LinkResolverFactory
         (getAfterDependents [_this] nil)
         (getBeforeDependents [_this] nil)
         (affectsGlobalScope [_this] false)
         (^LinkResolver apply [_this ^LinkResolverBasicContext _ctx]
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
                  (reify NodeRenderingHandler$CustomNodeRenderer
                    (render [_this node ctx html]
                      (let [resolved-link (.resolveLink ctx WikiLinkExtension/WIKI_LINK (.. node getLink unescape) nil)
                            url (.getUrl resolved-link)]
                        (.raw html (str "<a href=\"" url "\" data-source=\"wikilink\"><code>" (.. node getLink) "</code></a>"))))))})))))
      (extensions md-extensions)
      (build)))

(defn markdown-to-html
  "Parse the given string as Markdown and return HTML.

  A second argument can be passed to customize the rendring
  supported options:

  - `:escape-html?` if true HTML in input-str will be escaped"
  ([^String input-str]
   (markdown-to-html input-str {}))
  ([^String input-str opts]
   (->> (.parse md-container input-str)
        (.render (md-renderer opts))
        sanitize/clean)))

(defmulti render-text
  "An extension point for the rendering of different article types.

  `type` is determined by [[cljdoc.doc-tree/filepath->type]]."
  (fn [[type _contents]]
    type))

(defmethod render-text :cljdoc/markdown [[_ content]]
  (markdown-to-html content))

(defmethod render-text :cljdoc/asciidoc [[_ content]]
  (asciidoc-to-html content))

(defmulti determine-features
  "Rich text documents sometimes optionally need HTML/JavaScript features.
   For example an AsciiDoc article that uses STEM will require mathjax support."
  (fn [[type _contents]]
    type))

(defmethod determine-features :cljdoc/markdown [[_ _content]])

(defmethod determine-features :cljdoc/asciidoc [[_ content]]
  (when-let [doc-header (re-find #"(?s).*?\R\R" content)]
    (when (re-find #"(?m)^:stem:" doc-header)
      {:mathjax true})))

(comment
  (markdown-to-html "*hello world* <code>x</code>")

  (markdown-to-html "*hello world* <code>x</code>" {:escape-html? true})

  (markdown-to-html "*hello world* [[link]]" {:escape-html? true
                                              :render-wiki-link (constantly "???")})

  (asciidoc-to-html "ifdef::env-cljdoc[]\nCLJDOC\nendif::[]\nifndef::env-cljdoc[]\nNOT_CLJDOC\nendif::[]"))
