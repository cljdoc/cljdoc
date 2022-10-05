(ns cljdoc.util.fixref
  "Utilities to rewrite, or support the rewrite of, references in markdown rendered to HTML.
  For example, external links are rewritten to include nofollow, links to ingested SCM
  articles are rewritten to their slugs, and scm relative references are rewritten to point to SCM."
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cljdoc.util.scm :as scm]
            [cljdoc.server.routes :as routes])
  (:import (org.jsoup Jsoup)))

(defn- absolute-uri? [s]
  (or (.startsWith s "http://")
      (.startsWith s "https://")))

(defn- anchor-uri? [s]
  (.startsWith s "#"))

(defn- split-relpath-anchor
  "Returns `[relpath anchor]` for path `s`"
  [s]
  (rest (re-find #"/?([^#]*)(#.*$)?" s)))

(defn- root-relative-path? [s]
  (string/starts-with? s "/"))

(defn- get-cljdoc-url-prefix [s]
  (first (filter #(string/starts-with? s %) ["https://cljdoc.org" "https://cljdoc.xyz"])))

(defn- rebase-path
  "Rebase path `s1` to directory of relative path `s2`.
  When path `s1` is absolute it is returned."
  [s1 s2]
  (if (root-relative-path? s1)
    s1
    (let [p2-dir (if (string/ends-with? s2 "/")
                   s2
                   (.getParent (io/file s2)))]
      (str (io/file p2-dir s1)))))

(defn- normalize-path
  "Resolves relative `..` and `.` in path `s`"
  [s]
  (str (.normalize (.toPath (io/file s)))))

(defn- path-relative-to
  "Returns `file-path` from `from-dir-path`.
  Both paths must be relative or absolute."
  [file-path from-dir-path]
  (str (.relativize (.toPath (io/file from-dir-path))
                    (.toPath (io/file file-path)))))

(defn- error-ref
  "When the scm-file-path is unknown, as is currently the case for docstrings, we cannot handle relative refs
  and return a error ref."
  [ref scm-file-path]
  (when (and (not scm-file-path)
             (not (root-relative-path? ref)))
    "#!cljdoc-error!ref-must-be-root-relative!"))

(defn- fix-link
  "Return the cljdoc location for a given URL `href` or it's page on GitHub/GitLab etc."
  [href {:keys [scm-file-path target-path scm-base uri-map] :as _opts}]
  (or (error-ref href scm-file-path)
      (let [[href-rel-to-scm-base anchor]
            (-> href
                (rebase-path scm-file-path)
                normalize-path
                split-relpath-anchor)]
        (if-let [href-local-doc (get uri-map href-rel-to-scm-base)]
          (str (if target-path
                 (path-relative-to href-local-doc target-path)
                 href-local-doc)
               anchor)
          (str scm-base href-rel-to-scm-base anchor)))))

(defn- fix-image
  [src {:keys [scm-file-path scm-base]}]
  (or (error-ref src scm-file-path)
      (let [suffix (when (and (= :github (scm/provider scm-base))
                              (.endsWith src ".svg"))
                     "?sanitize=true")]
        (if (root-relative-path? src)
          (str scm-base (subs src 1) suffix)
          (str scm-base (-> src (rebase-path scm-file-path) normalize-path) suffix)))))

(defn uri-mapping
  "Returns lookup map where key is SCM repo relative file and value is cljdoc root relative `version-entity`
  slug path at for all `docs`.

  Ex: `{\"README.md\" \"/d/lread/cljdoc-exerciser/1.0.34/doc/readme}`"
  [version-entity docs]
  (->> docs
       (map (fn [d]
              [(-> d :attrs :cljdoc.doc/source-file)
               (->> (-> d :attrs :slug-path)
                    (string/join "/")
                    (assoc version-entity :article-slug)
                    (routes/url-for :artifact/doc :path-params))]))
       (into {})))

(defn- parse-html [html-str]
  (let [doc (Jsoup/parse html-str)
        props (.outputSettings doc)]
    (.prettyPrint props false)
    doc))

(defn fix
  "Rewrite references in HTML produced from rendering markdown.

  Markdown from SCM can contains references to images and articles.

  Relative <a> links are links to SCM:
  * an SCM link that is an article that has been imported to cljdoc => local link
    (slug for online, html file for offline)
  * else => SCM formatted (aka blob) link at correct revision

  Absolute <a> links
  * when relinking back to cljdoc.org => to root relative to support local testing
  * else => converted to nofollow link (this includes links rewritten to point SCM)

  Relative <img> references are links to SCM:
  - are converted to SCM raw references at correct revision
  - svg files from GitHub add special querystring parameters

  * `:html-str` the html of the content we are fixing

  * `fix-opts` map contains
    * `:scm-file-path` SCM repo home relative path of content
    * `:target-path` local relative destination path of content, if provided, used to relativize link paths local path (used for offline bundles)
    * `:uri-map` - map of relative scm paths to cljdoc doc slugs (or for offline bundles html files)
    * `:scm` - scm-info from bundle used to link to correct SCM file revision"
  [html-str {:keys [scm-file-path target-path scm uri-map] :as _fix-opts}]
  (let [doc (parse-html html-str)]
    (doseq [scm-relative-link (->> (.select doc "a")
                                   (map #(.attributes %))
                                   (remove #(= "wikilink" (.get % "data-source")))
                                   (remove #(absolute-uri? (.get % "href")))
                                   (remove #(anchor-uri? (.get % "href"))))]
      (let [fixed-link (fix-link
                        (.get scm-relative-link "href")
                        {:scm-file-path scm-file-path
                         :target-path target-path
                         :scm-base (scm/rev-formatted-base-url scm)
                         :uri-map uri-map})]
        (.put scm-relative-link "href" fixed-link)))

    (doseq [scm-relative-img (->> (.select doc "img")
                                  (map #(.attributes %))
                                  (remove #(absolute-uri? (.get % "src"))))]
      (.put scm-relative-img "src" (fix-image (.get scm-relative-img "src")
                                              {:scm-file-path scm-file-path
                                               :scm-base (scm/rev-raw-base-url scm)})))

    (doseq [absolute-link (->> (.select doc "a")
                               (map #(.attributes %))
                               (filter #(absolute-uri? (.get % "href"))))]
      (let [href (.get absolute-link "href")]
        (if-let [cljdoc-prefix (get-cljdoc-url-prefix href)]
          (.put absolute-link "href" (subs href (count cljdoc-prefix)))
          (.put absolute-link "rel" "nofollow"))))

    (.. doc body html toString)))

;; Some utilities to find which file in a git repository corresponds
;; to a file where a `def` is coming from --------------------------

(defn- find-full-filepath
  "Take a list of filepaths, one subpath and find the best matching full path.

  We use this to find the full path to files in Git for a given file in a jar."
  [known-files fpath]
  (let [matches (filter #(string/ends-with? (str "/" %) (str "/" fpath)) known-files)]
    (if (= 1 (count matches))
      (first matches)
      ;; choose shortest path where file sits under what looks like a src dir
      (let [best-guess (->> matches
                            (filter #(or (string/ends-with? (str "/" %) (str "/src/" fpath))
                                         (string/ends-with? (str "/" %) (str "/src/main/" fpath))))
                            (sort-by count)
                            first)]
        (if best-guess
          (log/warnf "Did not find unique file on SCM for jar file %s - chose %s from candidates: %s"
                     fpath best-guess (pr-str matches))
          (log/errorf "Did not find unique file on SCM for jar file %s - found no good candidate from candidates: %s"
                      fpath (pr-str matches)))
        best-guess))))

(defn match-files
  [known-files fpaths]
  {:pre [(seq known-files)]}
  (zipmap fpaths (map #(find-full-filepath known-files %) fpaths)))

(comment
  (require '[cljdoc.render.rich-text :as rich-text])

  (defn doc []
    (Jsoup/parse
     (rich-text/markdown-to-html (slurp "https://raw.githubusercontent.com/metosin/jsonista/master/README.md"))))

  (def fix-opts
    {:git-ls []
     :scm {:url "https://github.com/metosin/jsonista"
           :sha "07c59d1eadd458534c81d6ef8251b5fd5d754a74"}})

  (println (Jsoup/parse hs))

  (fix fp hs fo)

  (rebase "doc/coercion/coercion.md" "../ring/coercion.md")

  (rebase fp "route_syntax.md")

  (.relativize (.toPath (java.io.File. fp))
               (.toPath (java.io.File. "route_syntax.md")))

  (fix "README.md"
       (rich-text/markdown-to-html (slurp "https://raw.githubusercontent.com/metosin/jsonista/master/README.md"))
       fix-opts)

  (->> (.select (doc) "img")
       (map #(.attributes %))
       (remove #(or (.startsWith (.get % "src") "http://")
                    (.startsWith (.get % "src") "https://")))
       (map #(doto % (.put "src" (fix-image (.get % "src") fix-opts)))))

  (.get (.attributes (first (.select doc "a"))) "href"))
