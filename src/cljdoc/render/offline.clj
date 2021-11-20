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
            [cljdoc.util.scm :as scm]
            [cljdoc.platforms :as platf]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.render.assets :as assets]
            [cljdoc.render.rich-text :as rich-text]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [me.raynes.fs.compression :as fs-compression]
            [hiccup2.core :as hiccup]
            [hiccup.page])
  (:import (java.nio.file Files)
           (java.net URL)))

(def static-resource-prefix "resources-compiled/public/out")

(defn- ns-url
  [ns]
  {:pre [(string? ns)]}
  (str "api/" ns ".html"))

(defn- article-url
  [slug-path]
  {:pre [(string? (first slug-path))]}
  ;; WARN this could lead to overwriting files but nesting
  ;; directories complicates linking between files a lot and
  ;; so taking a shortcut here.
  (str "doc/" (string/join "-" slug-path) ".html"))

(defn- top-bar [version-entity scm-url sub-page?]
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
       (let [icon (get #{:github :gitlab} (scm/provider scm-url) :code)]
         [:img.v-mid.mr2 {:src (str "https://microicon-clone.vercel.app/" (name icon))}])
       [:span.dib (scm/coordinate scm-url)]])]])

(defn adjust-refs [sub-page? refs]
  (map #(cond->> % sub-page? (str "../"))
       refs))

(defn add-requested-features [sub-page? features]
  (when (:mathjax features)
    (list (layout/mathjax2-customizations {:show-math-menu false})
          (->> (assets/offline-js :mathjax)
               (adjust-refs sub-page?)
               (apply hiccup.page/include-js)))))

(defn- page [{:keys [version-entity namespace article-title scm-url page-features]} contents]
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
                   (->> (concat ["assets/cljdoc.css"] (assets/offline-css :tachyons))
                        (adjust-refs sub-page?)
                        (apply hiccup.page/include-css))]
                  [:div.sans-serif
                   (top-bar version-entity scm-url sub-page?)
                   [:div.absolute.bottom-0.left-0.right-0.overflow-scroll
                    {:style {:top "52px"}}
                    [:div.mw7.center.pa2.pb4
                     contents]]]
                  (->> (concat ["assets/js/index.js"]
                               (assets/offline-js :highlightjs))
                       (adjust-refs sub-page?)
                       (apply hiccup.page/include-js))
                  (layout/highlight-js-customization)
                  (add-requested-features sub-page? page-features)])))

(defn- article-toc
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

(defn- index-page [cache-bundle fix-opts]
  [:div
   (when (-> cache-bundle :version :doc)
     [:div
      [:h1.mv0.pv3 {:id "articles"} "Articles"]
      (article-toc (doctree/add-slug-path (-> cache-bundle :version :doc)))])

   [:h1.mv0.pv3 {:id "namespaces"} "Namespaces"]
   (for [ns (bundle/namespaces cache-bundle)
         :let [defs (bundle/defs-for-ns
                      (bundle/all-defs cache-bundle)
                      (platf/get-field ns :name))]]
     (api/namespace-overview ns-url ns defs fix-opts))])

(defn- doc-page [doc-tuple fix-opts]
  [:div
   [:div.markdown.lh-copy.pv4
    (hiccup/raw
     (fixref/fix (rich-text/render-text doc-tuple)
                 fix-opts))]])

(defn- ns-page [ns defs fix-opts]
  (let [ns-name (platf/get-field ns :name)
        render-wiki-link (api/render-wiki-link-fn ns-name #(str % ".html"))]
    [:div.ns-offline-page
     [:h1 ns-name]
     (api/render-doc ns render-wiki-link fix-opts)
     (for [def defs]
       (api/def-block def render-wiki-link fix-opts))]))

(defn- docs-files
  "Return a list of [file-path content] pairs describing a zip archive.

  Content may be a java.io.File or hiccup.util.RawString"
  [{:keys [version-entity] :as cache-bundle} static-resources]
  (cljdoc-spec/assert :cljdoc.spec/cache-bundle cache-bundle)
  (let [doc-tree     (doctree/add-slug-path (-> cache-bundle :version :doc))
        scm-info     (-> cache-bundle :version :scm)
        flat-doctree (-> doc-tree doctree/flatten*)
        uri-map (->> flat-doctree
                     (map (fn [d]
                            [(-> d :attrs :cljdoc.doc/source-file)
                             (article-url (-> d :attrs :slug-path))]))
                     (into {}))
        fix-opts {:scm scm-info :uri-map uri-map}
        page' (fn [opts contents]
                (page (assoc opts
                             :scm-url (-> cache-bundle :version :scm :url)
                             :version-entity version-entity)
                      contents))
        doc-attrs (->> flat-doctree
                       (filter #(-> % :attrs :cljdoc.doc/source-file))
                       (map #(assoc-in % [:attrs :title] (:title %)))
                       (map :attrs)
                       (map #(let [doc-type (:cljdoc.doc/type %)]
                               (assoc % :doc-tuple [doc-type (doc-type %)])))
                       (map #(assoc % :page-features
                                    (rich-text/determine-features (:doc-tuple %)))))
        ;; naive for now, assume a feature's value is always simply `true`
        lib-page-features (->> doc-attrs (map :page-features) (apply merge))]
    (reduce
     into
     [[["assets/cljdoc.css" (io/file (str static-resource-prefix (get static-resources "/main.css")))]]
      [["assets/js/index.js" (io/file (str static-resource-prefix (get static-resources "/main.js")))]]
      [["assets/js/index.js.map" (io/file (str static-resource-prefix (get static-resources "/main.js" ".map")))]]
      (assets/offline-assets :tachyons)
      (assets/offline-assets :highlightjs)
      [["index.html" (->> (index-page cache-bundle fix-opts)
                          (page' {}))]]

      ;; Optional assets
      (when (:mathjax lib-page-features)
        (assets/offline-assets :mathjax))

      ;; Documentation Pages / Articles
      (for [doc doc-attrs
            :let [target-file (article-url (:slug-path doc))]]
        [target-file
         (->> (doc-page (:doc-tuple doc)
                        (assoc fix-opts
                               :scm-file-path (:cljdoc.doc/source-file doc)
                               :target-path (.getParent (io/file target-file))))
              (page' {:article-title (:title doc)
                      :page-features (:page-features doc)}))])

      ;; Namespace Pages
      (for [ns-data (bundle/namespaces cache-bundle)
            :let [defs (bundle/defs-for-ns-with-src-uri cache-bundle (platf/get-field ns-data :name))
                  target-file (ns-url (platf/get-field ns-data :name))]]
        [target-file
         (->> (ns-page ns-data defs (assoc fix-opts
                                           ;; :scm-file-path - we don't currently have scm file for namespaces
                                           :target-path (.getParent (io/file target-file))))
              (page' {:namespace (platf/get-field ns-data :name)}))])])))

(defn zip-stream [{:keys [version-entity] :as cache-bundle} static-resources]
  (let [prefix (str (-> version-entity :artifact-id)
                    "-" (-> version-entity :version)
                    "/")]
    (->> (docs-files cache-bundle static-resources)
         (map (fn [[k v]]
                [(str prefix k)
                 (cond
                   (instance? URL v)                   (slurp v)
                   (instance? java.io.File v)          (Files/readAllBytes (.toPath v))
                   (instance? hiccup.util.RawString v) (.getBytes (str v))
                   :else (throw (Exception. (str "Unsupported value " (class v)))))]))
         (fs-compression/make-zip-stream))))

(comment
  (require '[cljdoc.storage.api :as storage]
           '[clojure.inspector :as i])

  (i/inspect-tree --c)

  (def --c (storage/bundle-docs (storage/->SQLiteStorage (cljdoc.config/db (cljdoc.config/config)))
                                #_{:group-id "reagent" :artifact-id "reagent" :version "0.8.1"}
                                {:group-id "re-frame" :artifact-id "re-frame" :version "0.10.5"}
                                #_{:group-id "manifold" :artifact-id "manifold" :version "0.1.6"}))

  (defn hiccup-raw-str? [x]
    (instance? hiccup.util.RawString x))

  (io/copy (zip-stream --c)
           (io/file "offline.zip"))

  (map first (docs-files --c))

  (->> (take 2 (docs-files --c))
       (map (fn [[k v]]
              [(str (-> --c :version-entity :artifact-id)
                    "-" (-> --c :version-entity :version)
                    "/" k)
               (cond
                 (instance?  java.io.File v)         (Files/readAllBytes (.toPath v))
                 (instance? hiccup.util.RawString v) (.getBytes (str v))
                 :else (throw (Exception. (str "Unsupported value " (class v)))))]))
       (fs-compression/zip "offline-docs.zip"))

  (slurp (URL. "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/highlight.min.js"))

  (slurp (io/input-stream "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/highlight.min.js")))


