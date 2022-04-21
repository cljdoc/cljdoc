(ns cljdoc.render.rich-text
  (:require [cljdoc.render.sanitize :as sanitize])
  (:import (org.asciidoctor Asciidoctor Asciidoctor$Factory Options)
           (com.vladsch.flexmark.parser Parser LinkRefProcessorFactory LinkRefProcessor)
           (com.vladsch.flexmark.html HtmlRenderer LinkResolverFactory LinkResolver)
           (com.vladsch.flexmark.html.renderer ResolvedLink LinkType LinkStatus LinkResolverBasicContext DelegatingNodeRendererFactory NodeRenderer NodeRenderingHandler NodeRenderingHandler$CustomNodeRenderer)
           (com.vladsch.flexmark.ext.tables TablesExtension)
           (com.vladsch.flexmark.ext.autolink AutolinkExtension)
           (com.vladsch.flexmark.ext.anchorlink AnchorLinkExtension)
           (com.vladsch.flexmark.ext.wikilink WikiLinkExtension WikiLink)
           (com.vladsch.flexmark.ext.wikilink.internal WikiLinkNodeRenderer$Factory)
           (com.vladsch.flexmark.util.data MutableDataSet DataHolder)
           #_{:clj-kondo/ignore [:unused-import]} ;; clj-kondo thinks we are not using Node
           (com.vladsch.flexmark.util.ast Document Node)
           (com.vladsch.flexmark.util.sequence BasedSequence)))

(def ^Asciidoctor adoc-container
  (Asciidoctor$Factory/create))

(defn asciidoc-to-html [^String file-content]
  (let [opts (doto (Options.)
               (.setAttributes (java.util.HashMap. {"env-cljdoc" true
                                                    "icons" "font"
                                                    "outfilesuffix" ".adoc"
                                                    "showtitle" true})))]
    (-> (.convert adoc-container file-content opts)
        sanitize/clean)))

(def md-extensions
  [(TablesExtension/create)
   (AutolinkExtension/create)
   (AnchorLinkExtension/create)])

(def md-parser-opts (doto (MutableDataSet.)
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

(defn- md-parser
  "Create a markdown parser
  options:
   :render-wiki-link - a lookup function for wikilink text inside [[]] -> href
                       on nil return for `whatever`, `[[whatever]]` will be
                       treated as regular markdown instead of as a wikilink."
  ^Parser [{:keys [render-wiki-link]}]
  (cond-> (Parser/builder md-parser-opts)

    render-wiki-link
    ;; Replicate the interesting parts of flexmark's WikiLinkLinkRefProcessor
    ;; while customizing to our use case.
    ;; We only want a wikilink to be considered such when it actually resolves.
    (.linkRefProcessorFactory
     (reify LinkRefProcessorFactory
       (getWantExclamationPrefix [_this _opts] false)
       (getBracketNestingLevel [_this _opts] 1)
       (^LinkRefProcessor apply [_this ^Document _doc]
         (reify LinkRefProcessor
           (getWantExclamationPrefix [_this] false)
           (getBracketNestingLevel [_this] 1)
           (^boolean isMatch [_this ^BasedSequence node-chars]
             (let [length (.length node-chars)]
               (and (>= length 5)
                    (= \[ (.charAt node-chars 0) (.charAt node-chars 1))
                    (= \] (.endCharAt node-chars 1) (.endCharAt node-chars 2))
                    (boolean (render-wiki-link (str (.subSequence node-chars 2 (- length 2))))))))
           (adjustInlineText [_this _doc node]
             (.getText node))
           (allowDelimiters [_this _chars _doc _node] false)
           (updateNodeElements [_this _doc _node])
           (^Node createNode [_this ^BasedSequence chars]
             (WikiLink. chars
                        true  ;; link is first, as in [[link|optional text]]
                        false ;; allow anchors
                        false ;; can escape pipe
                        false ;; can escape anchor
                        ))))))

    :always
    (-> (.extensions md-extensions)
        (.build))))

(def md-render-opts (doto (MutableDataSet.)
                      (.set AnchorLinkExtension/ANCHORLINKS_ANCHOR_CLASS "md-anchor")
                      (.set HtmlRenderer/FENCED_CODE_NO_LANGUAGE_CLASS "language-clojure")
                      (.toImmutable)))

(defn- md-renderer
  "Create a Markdown renderer.
  options:
   :escape-html? - when true escape all inline html
   :render-wiki-link - a lookup function for wikilink text inside [[]] -> href
                       assumes wikilink has been validated by parser."
  ^HtmlRenderer [{:keys [escape-html? render-wiki-link]}]
  (cond-> (HtmlRenderer/builder md-render-opts)
    :always
    (.escapeHtml (boolean escape-html?))

    render-wiki-link
    (-> (.linkResolverFactory
         (reify LinkResolverFactory
           (getAfterDependents [_this] nil)
           (getBeforeDependents [_this] nil)
           (affectsGlobalScope [_this] false)
           (^LinkResolver apply [_this ^LinkResolverBasicContext _ctx]
             (reify LinkResolver
               (resolveLink [_this _node _ctx link]
                 ;; our parser has already validated the link will resolve,
                 ;; otherwise we would not be here.
                 (if (= (.getLinkType link) WikiLinkExtension/WIKI_LINK)
                   (let [ref (.getUrl link)
                         resolved-ref (render-wiki-link ref)]
                     (ResolvedLink. LinkType/LINK
                                    resolved-ref
                                    nil ;; attributes
                                    LinkStatus/VALID))
                   link))))))
        (.nodeRendererFactory
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
                        (let [url (-> (.resolveLink ctx WikiLinkExtension/WIKI_LINK (-> node .getLink .unescape) nil)
                                      .getUrl)]
                          (.raw html (str "<a href=\"" url "\" data-source=\"wikilink\"><code>" (.getLink node) "</code></a>"))))))}))))))

    :always (-> (.extensions md-extensions)
                (.build))))

(defn markdown-to-html
  "Parse the given string as Markdown and return HTML.

  A second argument can be passed to customize the rendring
  supported options:

  - `:escape-html?` if true HTML in input-str will be escaped"
  ([^String input-str]
   (markdown-to-html input-str {}))
  ([^String input-str opts]
   (->> (.parse (md-parser opts) input-str)
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

  (markdown-to-html "*hello world* [[some/var|alt-text]]" {:escape-html? true
                                                           :render-wiki-link (constantly "target-url")})

  (markdown-to-html "*hello world* [[some/var]]" {:escape-html? true
                                                  :render-wiki-link (constantly "target-url")})

  (markdown-to-html "*hello world* [[something unresolved]]" {:escape-html? true
                                                              :render-wiki-link (constantly nil)})

  (markdown-to-html "*hello world* [[*something unresolved*]]" {:escape-html? true
                                                                :render-wiki-link (constantly nil)})

  (markdown-to-html "*hello world* [[some text]]" {:escape-html? true})

  (markdown-to-html "*hello world* [[**some text**]]" {:escape-html? true})

  (asciidoc-to-html "ifdef::env-cljdoc[]\nCLJDOC\nendif::[]\nifndef::env-cljdoc[]\nNOT_CLJDOC\nendif::[]"))
