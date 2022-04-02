(ns cljdoc.render.api-searchset
  "Renders a cache bundle into the format needed by the API to feed client-side search."
  (:require [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rt]
            [cljdoc.server.routes :as routes]
            [cljdoc.spec])
  (:import (org.jsoup Jsoup)))

(def header-tag? #{"h1" "h2" "h3" "h4" "h5" "h6"})

(defn path-for-doc
  [doc version-entity]
  (routes/url-for :artifact/doc
                  :params
                  (assoc version-entity
                         :article-slug
                         (get-in doc [:attrs :slug]))))

(defn path-for-namespace
  [{:keys [group-id artifact-id version]} name-of-ns]
  (routes/url-for :artifact/namespace :path-params {:group-id group-id
                                                    :artifact-id artifact-id
                                                    :version version
                                                    :namespace name-of-ns}))

(defn path-for-def
  [version-entity name-of-ns def-name]
  (str (path-for-namespace version-entity name-of-ns) "#" def-name))

(defn- elements->doc-segments
  "Does the tricky work of walking through the Jsoup node siblings and collecting
  headers and their text. Important considerations:

  1. Documents do not necessarily start with a header.
  2. Headers do not necessarily have text between them and the next header or the
     end of the document.
  3. Flexmark makes sure that all markdown headers have anchors, meaning this only works for markdown and not asciidoc."
  [elements doc-title]
  (loop [doc-segment {:title doc-title
                      :anchor nil
                      :text nil}
         [element & remaining-elements] elements
         doc-segments []]
    (cond
      (nil? element)
      (if (:text doc-segment)
        (conj doc-segments doc-segment)
        doc-segments)

      (header-tag? (.tagName element))
      (recur {:title (.text element)
              :anchor (-> element .children (.select ".md-anchor") .first .attributes (.get "id"))
              :text nil}
             remaining-elements
             (if (:text doc-segment)
               (conj doc-segments doc-segment)
               doc-segments))

      :else
      (recur (update doc-segment :text #(str % " " (.text element)))
             remaining-elements
             doc-segments))))

(defn- markdown-doc->docs
  [doc version-entity]
  (let [doc-title (:title doc)
        type-and-contents (doctree/entry->type-and-content doc)
        html (rt/render-text type-and-contents)
        doc (Jsoup/parse html)
        body-elements (-> doc (.getElementsByTag "body") first .children)
        doc-segments (elements->doc-segments body-elements doc-title)]
    (map #(let [title (if (:anchor %) (str doc-title " - " (:title %))
                          doc-title)
                url (str (path-for-doc doc version-entity) "#" (:anchor %))
                text (:text %)]
            {:name title
             :path url
             :doc text})
         doc-segments)))

(defn- generic-doc->docs
  [doc version-entity]
  (let [[type contents] (doctree/entry->type-and-content doc)]
    (when contents
      (let [html (rt/render-text [type contents])
            text (-> html Jsoup/parse (.getElementsByTag "body") first .text)]
        [{:name (:title doc)
          :path (path-for-doc doc version-entity)
          :doc text}]))))

(defn- doc->docs
  "Performs one of two operations, depending on document type:

  1. For markdown it takes a single cache-bundle doc and breaks it into multiple sections by header,
  each with their own name, doc, and URL path.
  2. For anything else it renders to HTML and then grabs all the text from the body as a single link."
  [doc version-entity]
  (case (:cljdoc.doc/type doc)
    :cljdoc/markdown (markdown-doc->docs doc version-entity)
    ;; TODO: asciidoc rendering
    (generic-doc->docs doc version-entity)))

(defn- ->namespaces
  "Renders cache-bundle `namespaces` into a format consumable by the API. This consists of:

  1. Paring down the fields in the cache-bundle's `namespaces`.
  2. Adding a URL path to the produced map."
  [cache-bundle-namespaces version-entity]
  (into []
        (comp (map #(select-keys % [:platform :name :doc]))
              (map #(assoc % :path (path-for-namespace version-entity (:name %)))))
        cache-bundle-namespaces))

(defn- ->searchset-members
  [members]
  (map #(select-keys % [:type :name :arglists :doc]) members))

(defn- ->defs
  "Renders cache-bundle `defs` into a format consumable by the API. This consists of:

  1. Paring down the fields in the cache-bundle's `defs`, including in the nested
     `:members` map..
  2. Adding a URL path to the produced map."
  [cache-bundle-defs version-entity]
  (into []
        (comp
         (map #(select-keys % [:platform :type :namespace :name :arglists :doc :members]))
         (map #(update % :members ->searchset-members))
         (map #(assoc % :path (path-for-def version-entity (:namespace %) (:name %)))))
        cache-bundle-defs))

(defn- ->docs
  "Renders cache-bundle `doc` tree down into finer-grained units of text and their links.
  To accomplish this the markdown is:

  1. Rendered to HTML.
  2. Parsed with Jsoup to find each header, each header's anchor, and all text after it
     preceeding either the next header or the end of the document.
  3. [header text, header anchor, text] is then transformed into [title, path, doc] where
     title is the document title and the header title, path is the path portion of the
     URL to deep-link to that section of the document, and doc is the text without HTML."
  [cache-bundle-docs version-entity]
  (vec (mapcat #(doc->docs % version-entity)
               (-> cache-bundle-docs doctree/add-slug-path doctree/flatten*))))

(defn cache-bundle->searchset
  [{namespaces :namespaces
    defs :defs
    {docs :doc} :version
    version-entity :version-entity
    :as _cache-bundle}]
  {:namespaces (->namespaces namespaces version-entity)
   :defs (->defs defs version-entity)
   :docs (->docs docs version-entity)})
