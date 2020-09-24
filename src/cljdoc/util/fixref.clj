(ns cljdoc.util.fixref
  "Utilities to rewrite, or support the rewrite of, references in markdown rendered to HTML.
  For example, external links are rewritten to include nofollow, links to ingested SCM
  articles are rewritten to their slugs, and scm relative references are rewritten to point to SCM."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [cljdoc.util.scm :as scm]
            [cljdoc.server.routes :as routes])
  (:import (org.jsoup Jsoup)))

(defn- absolute-uri? [s]
  (or (.startsWith s "http://")
      (.startsWith s "https://")))

(defn- anchor-uri? [s]
  (.startsWith s "#"))

(defn- own-uri? [s]
  (or (.startsWith s "https://cljdoc.org")
      (.startsWith s "https://cljdoc.xyz")))


(defn- rebase
  "Given a path `f1` and `f2` return a modified version of `f2` relative to `f1`

    Example:

       (rebase \"doc/basics/README.md\" \"route_syntax.md\") ;=> \"doc/basics/route_syntax.md\"
       (rebase \"doc/basics/README.md\" \"../introduction.md\") ;=> \"doc/introduction.md\""
  [f1 f2]
  (.toString (.normalize (.toPath (java.io.File. (.getParent (java.io.File. f1)) f2)))))

(defn- fix-link
  "Return the cljdoc location for a given URL or it's page on GitHub/GitLab etc."
  [href {:keys [scm-file-path scm-base uri-map] :as _opts}]
  (let [root-relative (if (.startsWith href "/")
                        (subs href 1)
                        (rebase scm-file-path href))
        w-o-anchor    (string/replace root-relative #"#.*$" "")
        anchor        (re-find #"#.*$" root-relative)]
    (if-let [from-uri-map (get uri-map w-o-anchor)]
      (str from-uri-map anchor)
      (str scm-base root-relative))))

(defn- fix-image
  [src {:keys [scm-file-path scm-base]}]
  (let [suffix (when (.endsWith src ".svg") "?sanitize=true")]
    (if (.startsWith src "/")
      (str scm-base (subs src 1) suffix)
      (str scm-base (rebase scm-file-path src) suffix))))

(defn uri-mapping [version-entity docs]
  (->> docs
       (map (fn [d]
              [(-> d :attrs :cljdoc.doc/source-file)
               (->> (-> d :attrs :slug-path)
                    (clojure.string/join "/")
                    (assoc version-entity :article-slug)
                    (routes/url-for :artifact/doc :path-params))]))
       (into {})))

(defn fix
  [html-str {:keys [scm-file-path git-ls scm uri-map] :as _fix-opts}]
  (let [doc     (Jsoup/parse html-str)
        scm-rev (or (:name (:tag scm))
                    (:commit scm))]
    (doseq [broken-link (->> (.select doc "a")
                             (map #(.attributes %))
                             (remove #(absolute-uri? (.get % "href")))
                             (remove #(anchor-uri? (.get % "href"))))]
      (let [fixed-link (fix-link
                        (.get broken-link "href")
                        {:scm-file-path scm-file-path
                         :scm-base (str (:url scm) "/blob/" scm-rev "/")
                         :uri-map uri-map})]
        (if (.startsWith fixed-link "doc/")
          ;; for offline bundles all articles are flat files in doc/
          ;; in this case we want just the filename to be the href
          (.put broken-link "href" (subs fixed-link 4))
          (.put broken-link "href" fixed-link))))

    (doseq [broken-img (->> (.select doc "img")
                            (map #(.attributes %))
                            (remove #(absolute-uri? (.get % "src"))))]
      (.put broken-img "src" (fix-image (.get broken-img "src")
                                        {:scm-file-path scm-file-path
                                         :scm-base (str "https://raw.githubusercontent.com/"
                                                        (scm/owner (:url scm)) "/"
                                                        (scm/repo (:url scm)) "/"
                                                        scm-rev "/")})))

    (doseq [ext-link (->> (.select doc "a")
                          (map #(.attributes %))
                          (filter #(absolute-uri? (.get % "href")))
                          (remove #(own-uri? (.get % "href"))))]
      (.put ext-link "rel" "nofollow"))

    (.. doc body html toString)))


;; Some utilities to find which file in a git repository corresponds
;; to a file where a `def` is coming from --------------------------

(defn- find-full-filepath
  "Take a list of filepaths, one subpath and find the best matching full path.

  We use this to find the full path to files in Git for a given file in a jar."
  [known-files fpath]
  (let [matches (filter #(or (= fpath %) (.endsWith % (str "/" fpath))) known-files)]
    (if (= 1 (count matches))
      (first matches)
      (do
        (log/warnf "Could not find unique file for fpath %s - canidates: %s" fpath (pr-str matches))
        (->> (filter #(.startsWith % "src") matches)
             first)))))

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

  (.get (.attributes (first (.select doc "a"))) "href")

  )
