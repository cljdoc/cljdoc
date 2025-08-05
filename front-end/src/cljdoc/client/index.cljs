(ns cljdoc.client.index
  "Entry point for cljdoc front-end support.
  Various initializers are called; they decide if they need to be invoked."
  (:require ["./hljs-merge-plugin" :refer [mergeHTMLPlugin]]
            [cljdoc.client.docstring-toggler :as docstring-toggler]
            [cljdoc.client.hljs-copy-button-plugin :refer [copyButtonPlugin]]
            [cljdoc.client.lib-search :as lib-search]
            [cljdoc.client.lib-switcher :as lib-switcher]
            [cljdoc.client.mobile :as mobile]
            [cljdoc.client.namespace-scroll :as namespace-scroll]
            [cljdoc.client.project-doc :as project-doc]
            [cljdoc.client.recent-doc-links :as recent-doc-links]
            [cljdoc.client.single-docset-search :as single-docset-search]
            [cljdoc.client.versions-form :as versions-form]))

(warn-on-lazy-reusage!)

(lib-switcher/init)
(lib-search/init)
(versions-form/init)
(docstring-toggler/init)
(namespace-scroll/init)
(mobile/init)
(project-doc/init)
(recent-doc-links/init)
(single-docset-search/init)

(set! js/window.mergeHTMLPlugin mergeHTMLPlugin)
(set! js/window.copyButtonPlugin copyButtonPlugin)
