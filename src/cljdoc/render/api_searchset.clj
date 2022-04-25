(ns cljdoc.render.api-searchset
  "Renders a cache bundle into the format needed by the API to feed client-side search."
  (:require [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rt]
            [cljdoc.server.routes :as routes]
            [cljdoc.spec]
            [clojure.string :as str])
  (:import (org.jsoup Jsoup)))

(defn path-for-doc
  [doc version-entity]
  (routes/url-for :artifact/doc
                  :params
                  (assoc version-entity
                         :article-slug
                         (str/join "/" (or (get-in doc [:attrs :slug-path])
                                           [(get-in doc [:attrs :slug])])))))

(defn path-for-namespace
  [{:keys [group-id artifact-id version]} name-of-ns]
  (routes/url-for :artifact/namespace :path-params {:group-id group-id
                                                    :artifact-id artifact-id
                                                    :version version
                                                    :namespace name-of-ns}))

(defn path-for-def
  [version-entity name-of-ns def-name]
  (str (path-for-namespace version-entity name-of-ns) "#" def-name))

(defn- soup-elem?
  "Returns true if Jsoup elem is an html element (as oppposed to a text node)"
  [elem]
  (instance? org.jsoup.nodes.Element elem))

(defn heading-elem?
  [elem]
  (and
   (soup-elem? elem)
   (#{"h1" "h2" "h3" "h4" "h5" "h6"} (.tagName elem))
   (= "a" (some-> elem .children first .tagName))))

(defn- adoc-section? [elem]
  (and (soup-elem? elem)
       (= "div" (.tagName elem))
       (.hasAttr elem "class")
       (re-matches #"sect[1-9]" (.attr elem "class"))
       (heading-elem? (first (.children elem)))))

(defn- adoc-section-title [section-elem]
  (-> section-elem .children .first .text))

(defn- adoc-section-anchor [section-elem]
  (-> section-elem .children .first .attributes (.get "id")))

(defn- append-text [s elem]
  (let [t (-> elem .text str/trim)]
    (if (= "" t)
      s
      (str s t " "))))

(defn adoc-section-text [section-elem]
  (let [elems (if (= "sectionbody" (some-> section-elem
                                           .children
                                           first
                                           .nextElementSibling
                                           (.attr "class")))
                (-> section-elem .children .first .nextElementSibling .childNodes)
                (-> section-elem .childNodes))]
    (reduce (fn [acc elem]
              (if (or (adoc-section? elem) (heading-elem? elem))
                acc
                (append-text acc elem)))
            ""
            elems)))

(defn adoc-soup->doc-segments
  "Returns document segments for a JSoup parsed `body-elem` AsciiDoc with `doc-title`.

  For adoc, the <a href> points to the header tag.
  ```
  <h2 id=x><a href=x>Formatting marks</a></h2>
  ```

  Adoc uses a nested structure.
  The sect3 div is under the sect2 div which is under the sect1 div.

  Adoc is a bit odd, in that sect1 plunks its content under a sectionbody div
  But... does not seem to do this for deeper sections.

  ```
  <body>
   blah
   <div class=sect1>
    <h2 id=x><a href=#x>Heading</a></h2>
    <div class=sectionbody>
     blah
     blah
     <div class=sect2>
      <h3 id=y><a href=#y>Heading</a><h3>
      blah
      blah
     </div>
    </div>
   </div>
  </body>

  Also: adoc documents can end with trailing non-header content like footers."
  [body-elem doc-title]
  (let [all-nodes (tree-seq #(.childNodes %) #(.childNodes %) body-elem)]
    (reduce (fn [acc elem]
              (if (adoc-section? elem)
                (conj acc
                      {:title (adoc-section-title elem)
                       :anchor (adoc-section-anchor elem)
                       :text (adoc-section-text elem)})
                acc))
            [{:title doc-title
              :anchor nil
              :text (adoc-section-text body-elem)}]
            all-nodes)))

(defn- md-soup->doc-segments
  "Returns document segments for a JSoup parsed `body-elem` CommonMark doc with `doc-title`.

  For md, links to `<a>` ref points to itself:
  ```
  <h2><a href=x id=x>Formatting marks</a></h2>
  ```
  The md structure is simple and flat."
  [body-elem doc-title]
  (loop [doc-segment {:title doc-title
                      :anchor nil
                      :text nil}
         [element & remaining-elements] (.childNodes body-elem)
         doc-segments []]
    (cond
      (nil? element)
      (if (:text doc-segment)
        (conj doc-segments doc-segment)
        doc-segments)

      (heading-elem? element)
      (recur {:title (.text element)
              :anchor (-> element .children (.select ".md-anchor") .first .attributes (.get "id"))
              :text nil}
             remaining-elements
             (if (:text doc-segment)
               (conj doc-segments doc-segment)
               doc-segments))

      :else
      (recur (update doc-segment :text #(append-text % element))
             remaining-elements
             doc-segments))))

(defn doc->doc-segments [doc]
  (let [doc-tuple (doctree/entry->type-and-content doc)
        [doc-type contents] doc-tuple]
    (when contents
      (let [html (rt/render-text doc-tuple)
            doc-elements (Jsoup/parse html)
            body-elements (-> doc-elements (.getElementsByTag "body") .first)
            doc-title (:title doc)]
        (case doc-type
          :cljdoc/markdown (md-soup->doc-segments body-elements doc-title)
          :cljdoc/asciidoc (adoc-soup->doc-segments body-elements doc-title))))))

(defn- doc->docs
  "Returns `doc` broken down into headings with any text belong to those headings.
  Url paths are resolved from `version-entity`.

  The text for a heading should not include text for any of its sub-headings.

  For a conceptual example:
   heading1
    contenta
    heading2
     contentb

  The text for heading1 should only contain contenta.

  The root heading is the doc title; any text not belonging to any heading will belong
  to the root heading.

  All headings should be included even if they have no text.

  Important considerations:
  1. Documents do not necessarily start with a header.
  2. Headers do not necessarily have text between them and the next header or the
     end of the document."
  [doc version-entity]
  (when-let [doc-segments (doc->doc-segments doc)]
    (let [doc-title (:title doc)]
      (map #(let [title (if (:anchor %) (str doc-title " - " (:title %))
                            doc-title)
                  url (str (path-for-doc doc version-entity) "#" (:anchor %))
                  text (:text %)]
              {:name title
               :path url
               :doc text})
           doc-segments))))

(defn- ->namespaces
  "Renders cache-bundle `namespaces` into a format consumable by the API. This consists of:

  1. Paring down the fields in the cache-bundle's `namespaces`.
  2. Adding a URL path to the produced map.
  3. Sorting for a deterministic ordering."
  [cache-bundle-namespaces version-entity]
  (->> (into []
             (comp (map #(select-keys % [:platform :name :doc]))
                   (map #(assoc % :path (path-for-namespace version-entity (:name %)))))
             cache-bundle-namespaces)
       (sort-by (juxt :name :platform))
       vec))

(defn- ->searchset-members
  [members]
  (map #(select-keys % [:type :name :arglists :doc]) members))

(defn- ->defs
  "Renders cache-bundle `defs` into a format consumable by the API. This consists of:

  1. Paring down the fields in the cache-bundle's `defs`, including in the nested
     `:members` map..
  2. Adding a URL path to the produced map.
  3. Sorting for a deterministic ordering"
  [cache-bundle-defs version-entity]
  (->> (into []
             (comp
              (map #(select-keys % [:platform :type :namespace :name :arglists :doc :members]))
              (map #(update % :members ->searchset-members))
              (map #(assoc % :path (path-for-def version-entity (:namespace %) (:name %)))))
             cache-bundle-defs)
       (sort-by (juxt :namespace :name :platform))
       vec))

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

(comment
  (require '[clojure.edn :as edn])

  (def cb (-> "resources/test_data/cache_bundle.edn"
              slurp
              edn/read-string))

  (-> cb :version :doc)
  (def sr (cache-bundle->searchset cb))

  ;; adoc play
  (-> "<body>hello
        pre content
        <div class=\"sect1\">
          <h2 id=\"a-id\"><a href=\"#a-id\">a-title</a></h2>
          <div class=sectionbody>
            a-content
            <p>more a-content!</p>
            <div class=\"sect2\">
              <h3 id=\"b-id\"><a href=\"#b-id\">b-title</a></h3>
              b-content
            </div>
            <div class=\"sect2\">
              <h3 id=\"c-id\"><a href=\"#c-id\">c-title</a></h3>
            </div>
          </div>
        </div>
        post section content
        <p>more-post!</p>
        <h2>this aint no section header</h2>
      </body>"
      Jsoup/parse
      (.getElementsByTag "body")
      .first
      (adoc-soup->doc-segments "My doc title"))

  ;; md play
  (-> "<body>
        content before the first header
        <h1><a id=\"a-id\" class=\"md-anchor\" href=\"#a-id\">a-title</a></h1>
        a-content
        <p>more a-content!</p>

        <h2><a id=\"b-id\" class=\"md-anchor\" href=\"#b-id\">b-title</a></h2>
        b-content

        <h1><a id=\"c-id\" class=\"md-anchor\" href=\"#c-id\">c-title</a></h1>

       </body>"
      Jsoup/parse
      (.getElementsByTag "body")
      .first
      (md-soup->doc-segments "My doc title"))

  nil)
