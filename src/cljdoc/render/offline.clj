(ns cljdoc.render.offline
  "Rendering code for offline bundles.

  While more reuse would be possible this is intentionally
  kept somewhat separately as DOM stability is more important
  for tools like Dash etc."
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.render.api :as api]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.spec :as cljdoc-spec]
            [cljdoc.bundle :as bundle]
            [cljdoc.util :as util]
            [cljdoc.platforms :as platf]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.render.rich-text :as rich-text]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [me.raynes.fs.compression :as fs-compression]
            [hiccup2.core :as hiccup]
            [hiccup.page])
  (:import (java.nio.file Files)))

(defn ns-url
  [ns]
  {:pre [(string? ns)]}
  (str "api/" ns ".html"))

(defn article-url
  [slug-path]
  {:pre [(string? (first slug-path))]}
  ;; WARN this could lead to overwriting files but nesting
  ;; directories complicates linking between files a lot and
  ;; so taking a shortcut here.
  (str "doc/" (string/join "-" slug-path) ".html"))

(defn top-bar [version-entity scm-url sub-page?]
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a.dib.v-mid.link.dim.black.b.f6.mr3
    {:href (if sub-page? ".." "#")}
    (util/clojars-id version-entity)]
   [:span.dib.v-mid.gray.f6.mr3
    (:version version-entity)]
   [:a.link.blue.ml3 {:href (if sub-page? "../index.html#namespaces" "#namespaces")} "Namespaces"]
   [:a.link.blue.ml3 {:href (if sub-page? "../index.html#articles" "#articles")} "Articles"]
   [:div.tr
    {:style {:flex-grow 1}}
    (when scm-url
      [:a.link.dim.gray.f6.tr
       {:href scm-url}
       [:img.v-mid.mr2 {:src "https://icon.now.sh/github"}]
       [:span.dib (util/gh-coordinate scm-url)]])]])

(defn page [{:keys [version-entity namespace article-title scm-url]} contents]
  (let [sub-page? (or namespace article-title)]
    (hiccup/html {:mode :html}
                 (hiccup.page/doctype :html5)
                 [:html {}
                  [:head
                   [:title
                    (str
                     (some-> sub-page? (str " — "))
                     (util/clojars-id version-entity) " v"
                     (:version version-entity))]
                   [:meta {:charset "utf-8"}]
                   (hiccup.page/include-css
                    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/styles/github-gist.min.css"
                    "https://unpkg.com/tachyons@4.9.0/css/tachyons.min.css"
                    (if sub-page? "../cljdoc.css" "cljdoc.css"))]
                  [:div.sans-serif
                   (top-bar version-entity scm-url sub-page?)
                   [:div.absolute.bottom-0.left-0.right-0.overflow-scroll
                    {:style {:top "52px"}}
                    [:div.mw7.center.pa2.pb4
                     contents]]]
                  (layout/highlight-js)])))

(defn article-toc
  "Very similar to `doc-tree-view` but uses the offline-docs
  link/url generation mechanism"
  [doc-tree]
  (->> doc-tree
       (map (fn [doc-page]
              (let [slug-path (-> doc-page :attrs :slug-path)]
                [:li.mv1
                 (if (-> doc-page :attrs :cljdoc.doc/source-file)
                   [:a.link.blue.hover-dark-blue.dib
                    {:href  (article-url slug-path)}
                    (:title doc-page)]
                   [:span (:title doc-page)])
                 (some-> doc-page :children seq article-toc)])))
       (into [:ol])))

(defn index-page [{:keys [cache-contents] :as cache-bundle}]
  [:div
   (when (-> cache-contents :version :doc)
     [:div
      [:h1.mv0.pv3 {:id "articles"} "Articles"]
      (article-toc (doctree/add-slug-path (-> cache-contents :version :doc)))])

   [:h1.mv0.pv3 {:id "namespaces"} "Namespaces"]
   (for [ns (bundle/namespaces cache-bundle)
         :let [defs (bundle/defs-for-ns
                      (:defs cache-contents)
                      (platf/get-field ns :name))]]
     (api/namespace-overview ns-url ns defs))])

(defn doc-page [doc-p fix-opts]
  [:div
   [:div.markdown.lh-copy.pv4
    (hiccup/raw
     (fixref/fix (-> doc-p :attrs :cljdoc.doc/source-file)
                 (or (some-> doc-p :attrs :cljdoc/markdown rich-text/markdown-to-html)
                     (some-> doc-p :attrs :cljdoc/asciidoc rich-text/asciidoc-to-html))
                 fix-opts))]])

(defn ns-page [ns defs {:keys [scm-base file-mapping]}]
  (let [ns-name (platf/get-field ns :name)
        render-wiki-link (api/render-wiki-link-fn ns-name #(str % ".html"))]
    [:div
     [:h1 ns-name]
     (api/render-doc ns render-wiki-link)
     (for [def defs]
       (api/def-block
         (api/add-src-uri def scm-base file-mapping)
         render-wiki-link))]))

(defn docs-files
  "Return a list of [file-path content] pairs describing a zip archive.

  Content may be a java.io.File or hiccup.util.RawString"
  [{:keys [cache-contents cache-id] :as cache-bundle}]
  (cljdoc-spec/assert :cljdoc.spec/cache-bundle cache-bundle)
  (let [doc-tree     (doctree/add-slug-path (-> cache-contents :version :doc))
        scm-info     (-> cache-contents :version :scm)
        blob         (or (:name (:tag scm-info)) (:commit scm-info))
        scm-base     (str (:url scm-info) "/blob/" blob "/")
        file-mapping (when (:files scm-info)
                       (fixref/match-files
                        (keys (:files scm-info))
                        (set (keep :file (-> cache-contents :defs)))))
        flat-doctree (-> doc-tree doctree/flatten*)
        uri-map (->> flat-doctree
                       (map (fn [d]
                              [(-> d :attrs :cljdoc.doc/source-file)
                               (article-url (-> d :attrs :slug-path))]))
                       (into {}))
        page'   (fn [type title contents]
                  (page {:version-entity cache-id
                         :scm-url (-> cache-contents :version :scm :url)
                         type title}
                        contents))]


    (reduce
     into
     [[["cljdoc.css" (io/file (io/resource "public/cljdoc.css"))]
       ["index.html" (->> (index-page cache-bundle)
                          (page' nil nil))]]

      ;; Documentation Pages / Articles
      (for [doc-p (filter #(-> % :attrs :cljdoc.doc/source-file) flat-doctree)]
        [(article-url (-> doc-p :attrs :slug-path))
         (->> (doc-page doc-p {:scm scm-info :uri-map uri-map})
              (page' :article-title (:title doc-p)))])

      ;; Namespace Pages
      (for [ns-data (bundle/namespaces cache-bundle)
            :let [defs (bundle/defs-for-ns
                         (:defs cache-contents)
                         (platf/get-field ns-data :name))]]
        [(ns-url (platf/get-field ns-data :name))
         (->> (ns-page ns-data defs {:scm-base scm-base :file-mapping file-mapping})
              (page' :namespace (platf/get-field ns-data :name)))])])))

(defn zip-stream [{:keys [cache-id] :as cache-bundle}]
  (let [prefix (str (-> cache-id :artifact-id)
                    "-" (-> cache-id :version)
                    "/")]
    (->> (docs-files cache-bundle)
         (map (fn [[k v]]
                [(str prefix k)
                 (cond
                   (instance? java.io.File v)         (Files/readAllBytes (.toPath v))
                   (instance? hiccup.util.RawString v) (.getBytes (str v))
                   :else (throw (Exception. (str "Unsupported value " (class v)))))]))
         (fs-compression/make-zip-stream))))

(comment
  (require '[cljdoc.storage.api :as storage]
           '[clojure.inspector :as i])

  (i/inspect-tree --c)

  (def --c (storage/bundle-docs (storage/->GrimoireStorage (io/file "data" "grimoire"))
                                #_{:group-id "reagent" :artifact-id "reagent" :version "0.8.1"}
                                {:group-id "re-frame" :artifact-id "re-frame" :version "0.10.5"}
                                #_{:group-id "manifold" :artifact-id "manifold" :version "0.1.6"}))

  (defn hiccup-raw-str? [x]
    (instance? hiccup.util.RawString x))

  (zip-stream --c)

  (map first (docs-files --c))

  (->> (take 2 (docs-files --c))
       (map (fn [[k v]]
              [(str (-> --c :cache-id :artifact-id)
                    "-" (-> --c :cache-id :version)
                    "/" k)
               (cond
                 (instance?  java.io.File v)         (Files/readAllBytes (.toPath v))
                 (instance? hiccup.util.RawString v) (.getBytes (str v))
                 :else (throw (Exception. (str "Unsupported value " (class v)))))]))
       (fs-compression/zip "offline-docs.zip"))

  )
