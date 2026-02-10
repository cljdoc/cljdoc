(ns cljdoc.render.api-searchset
  "Renders a docset into the format needed by the API to feed client-side search."
  (:require [cljdoc-shared.proj :as proj]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rt]
            [cljdoc.server.routes :as routes]
            [cljdoc.spec]
            [cljdoc.user-config :as user-config]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Element TextNode)))

(set! *warn-on-reflection* true)

(defn path-for-doc
  [doc version-entity]
  (routes/url-for :artifact/doc
                  :path-params
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
  (instance? Element elem))

(defn heading-elem?
  [^Element elem]
  (and
   (soup-elem? elem)
   (#{"h1" "h2" "h3" "h4" "h5" "h6"} (.tagName elem))
   (= "a" (some-> elem .children .first .tagName))))

(defn- md-section?
  [^Element elem]
  (and (heading-elem? elem)
       (let [child (.firstElementChild elem)]
         (and child
              (= "a" (.tagName child))
              (not= "" (.id child))))))

(defn- adoc-section? [^Element elem]
  (and (soup-elem? elem)
       (= "div" (.tagName elem))
       (.hasAttr elem "class")
       (re-matches #"sect[1-9]" (.attr elem "class"))
       (heading-elem? (first (.children elem)))))

(defn- adoc-section-title [^Element section-elem]
  (-> section-elem .children .first .text))

(defn- adoc-section-anchor [^Element section-elem]
  (-> section-elem .children .first .attributes (.get "id")))

(defprotocol ElemText
  (text [elem]))

;; I don't see a common way to get .text for JSoup nodes, perhaps
;; a bit contrived way to avoid reflection warnings?
(extend-protocol ElemText
  Element
  (text [elem] (.text elem))
  TextNode
  (text [elem] (.text elem)))

(defn- append-text [s elem]
  (let [t (-> elem text str/trim)]
    (if (= "" t)
      s
      (str s t " "))))

(defn adoc-section-text [^Element section-elem]
  (let [elems (if (= "sectionbody" (some-> section-elem
                                           .children
                                           .first
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
  [^Element body-elem doc-title]
  (let [all-nodes (tree-seq (fn [^Element e] (.childNodes e))
                            (fn [^Element e] (.childNodes e)) body-elem)]
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
  [^Element body-elem doc-title]
  (loop [doc-segment {:title doc-title
                      :anchor nil
                      :text nil}
         [^Element element & remaining-elements] (.childNodes body-elem)
         doc-segments []]
    (cond
      (nil? element)
      (if (:text doc-segment)
        (conj doc-segments doc-segment)
        doc-segments)

      (md-section? element)
      (recur {:title (.text element)
              :anchor (-> element .firstElementChild .id)
              :text nil}
             remaining-elements
             (if (:text doc-segment)
               (conj doc-segments doc-segment)
               doc-segments))

      :else
      (recur (update doc-segment :text #(append-text % element))
             remaining-elements
             doc-segments))))

(defn- plaintext-soup->doc-segments
  "Returns document segments for a JSoup parsed `body-elem` plaintext doc with `doc-title`.

  Plain text is not sectioned or segmented, we just have one big blob."
  [^Element body-elem doc-title]
  [{:title doc-title
    :anchor nil
    :text (.text body-elem)}])

(defn doc->doc-segments [doc]
  (let [doc-tuple (doctree/entry->type-and-content doc)
        [doc-type contents] doc-tuple]
    (when contents
      (let [html (rt/render-text doc-tuple)
            doc-elements (Jsoup/parse ^String html)
            body-elements (-> doc-elements (.getElementsByTag "body") .first)
            doc-title (:title doc)]
        (case doc-type
          :cljdoc/markdown (md-soup->doc-segments body-elements doc-title)
          :cljdoc/asciidoc (adoc-soup->doc-segments body-elements doc-title)
          :cljdoc/plaintext (plaintext-soup->doc-segments body-elements doc-title))))))

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

(defn- docstring-text [s {:keys [docstring-format]}]
  (if (= :cljdoc/plaintext docstring-format)
    s
    (let [html (rt/markdown-to-html s {:no-emojis? true
                                       :escape-html? true
                                       ;; effectively render wikilinks as their name for searchability
                                       :render-wiki-link (fn [wikilink-ref] wikilink-ref)})
          doc-elements (Jsoup/parse ^String html)
          body-element (-> doc-elements (.getElementsByTag "body") .first)]
      (.text body-element))))

(defn- update-if-exists [m k f]
  (if (contains? m k)
    (update m k f)
    m))

(defn- ->namespaces
  "Renders docset `namespaces` into a format consumable by the API. This consists of:

  1. Paring down the fields in the docset's `namespaces`.
  2. Adding a URL path to the produced map.
  3. Sorting for a deterministic ordering."
  [docset-namespaces version-entity api-opts]
  (->> (into []
             (comp (map #(select-keys % [:platform :name :doc]))
                   (map #(update-if-exists % :doc (fn [s] (docstring-text s api-opts))))
                   (map #(assoc % :path (path-for-namespace version-entity (:name %)))))
             docset-namespaces)
       (sort-by (juxt :name :platform))
       vec))

(defn- ->searchset-members
  [members version-entity parent]
  (->> members
       (mapv #(select-keys % [:type :name :arglists :doc]))
       (mapv #(assoc % :path (path-for-def version-entity (:namespace parent) (:name %))))))

(defn- ->deregexify [x]
  (walk/postwalk #(if (instance? java.util.regex.Pattern %)
                    (str %)
                    %)
                 x))

(defn- ->defs
  "Renders docset `defs` into a format consumable by the API. This consists of:

  1. Paring down the fields in the docset's `defs`, including in the nested
     `:members` map..
  2. Adding a URL path to the produced map.
  3. Converting any regexes in arglists to strings (to be JSON friendly).
  4. Sorting for a deterministic ordering"
  [docset-defs version-entity api-opts]
  (->> (into []
             (comp
              (map #(select-keys % [:platform :type :namespace :name :arglists :doc :members]))
              (map #(update-if-exists % :doc (fn [s] (docstring-text s api-opts))))
              (map #(update-if-exists % :arglists ->deregexify))
              (map #(update % :members ->searchset-members version-entity %))
              (map #(assoc % :path (path-for-def version-entity (:namespace %) (:name %)))))
             docset-defs)
       (sort-by (juxt :namespace :name :platform))
       vec))

(defn- ->docs
  "Renders docset `doc` tree down into finer-grained units of text and their links.
  To accomplish this the markdown is:

  1. Rendered to HTML.
  2. Parsed with Jsoup to find each header, each header's anchor, and all text after it
     preceeding either the next header or the end of the document.
  3. [header text, header anchor, text] is then transformed into [title, path, doc] where
     title is the document title and the header title, path is the path portion of the
     URL to deep-link to that section of the document, and doc is the text without HTML."
  [docset-docs version-entity]
  (vec (mapcat #(doc->docs % version-entity)
               (-> docset-docs doctree/add-slug-path doctree/flatten*))))

(defn docset->searchset
  [docset]
  (let [{:keys [namespaces version version-entity defs]} docset
        {:keys [doc config]} version
        docs doc
        api-opts {:docstring-format (user-config/docstring-format config (proj/clojars-id version-entity))}]
    {:namespaces (->namespaces namespaces version-entity api-opts)
     :defs (->defs defs version-entity api-opts)
     :docs (->docs docs version-entity)}))

(comment
  (require '[clojure.edn :as edn])

  (def ds (-> "resources/test_data/docset.edn"
              slurp
              edn/read-string))

  (-> ds :version :doc)
  (def sr (docset->searchset ds))

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

  (docstring-text "Hello `var` [[link]]" {:docstring-format :foo})
  ;; => "Hello var link"
  (docstring-text "Hello `var` [[link]]" {:docstring-format :cljdoc/plaintext})
  ;; => "Hello `var` [[link]]"

  :eoc)
