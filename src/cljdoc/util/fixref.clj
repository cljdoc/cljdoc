(ns cljdoc.util.fixref
  "Utilities to fix broken references in HTML that was intended to be
  rendered in other places, e.g. GitHub."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [cljdoc.util :as util]
            [cljdoc.server.routes :as routes])
  (:import (org.jsoup Jsoup)))

;; inputs
;; - list of files in in git repo
;; - info about git repo (remote + sha)
;; - doctree
;; - project-info (group-id, artifact-id, version)
;; - path of file being fixed

(defn- absolute-uri? [s]
  (or (.startsWith s "http://")
      (.startsWith s "https://")))

(defn- anchor-uri? [s]
  (.startsWith s "#"))

(defn uri-mapping [cache-id docs]
  (->> docs
       (map (fn [d]
              [(-> d :attrs :cljdoc.doc/source-file)
               (->> (-> d :attrs :slug-path)
                    (clojure.string/join "/")
                    (assoc cache-id :article-slug)
                    (routes/url-for :artifact/doc :path-params))]))
       (into {})))

(defn- rebase
  "Given a path `f1` and `f2` return a modified version of `f2` relative to `f1`

    Example:

       (rebase \"doc/basics/README.md\" \"route_syntax.md\") ;=> \"doc/basics/route_syntax.md\"
       (rebase \"doc/basics/README.md\" \"../introduction.md\") ;=> \"doc/introduction.md\""
  [f1 f2]
  (.toString (.normalize (.toPath (java.io.File. (.getParent (java.io.File. f1)) f2)))))

(defn fix-link
  [file-path href {:keys [scm-base uri-map]}]
  (let [root-relative    (if (.startsWith href "/")
                           (subs href 1)
                           (rebase file-path href))
        w-o-anchor       (string/replace root-relative #"#.*$" "")
        anchor           (re-find #"#.*$" root-relative)]
    ;; (prn 'file-path file-path)
    ;; (prn 'href href)
    ;; (prn 'w-o-anchor w-o-anchor)
    ;; (prn 'from-uri-map  (get uri-map w-o-anchor))
    ;; (prn 'keys-uri-map  (keys uri-map))
    (if-let [from-uri-map (get uri-map w-o-anchor)]
      (-> (get uri-map file-path)
          ;; TODO check if relative links will work consistently
          (util/strip-common-start-string from-uri-map)
          (str anchor))
      (str scm-base root-relative))))

(defn fix-image
  [file-path src {:keys [scm-base]}]
  (let [suffix (when (.endsWith src ".svg") "?sanitize=true")]
    (if (.startsWith src "/")
      (str scm-base (subs src 1) suffix)
      (str scm-base (rebase file-path src) suffix))))

(defn fix
  [file-path html-str {:keys [git-ls scm uri-map] :as fix-opts}]
  ;; (def fp file-path)
  ;; (def hs html-str)
  ;; (def fo fix-opts)
  (let [doc     (Jsoup/parse html-str)
        scm-rev (or (:name (:tag scm))
                    (:commit scm))]
    (doseq [broken-link (->> (.select doc "a")
                             (map #(.attributes %))
                             (remove #(absolute-uri? (.get % "href")))
                             (remove #(anchor-uri? (.get % "href"))))]
      (.put broken-link "href" (fix-link file-path
                                         (.get broken-link "href")
                                         {:scm-base (str (:url scm) "/blob/" scm-rev "/")
                                          :uri-map uri-map})))

    (doseq [broken-img (->> (.select doc "img")
                            (map #(.attributes %))
                            (remove #(absolute-uri? (.get % "src"))))]
      (.put broken-img "src" (fix-image file-path
                                        (.get broken-img "src")
                                        {:scm-base (str "https://raw.githubusercontent.com/"
                                                        (util/gh-owner (:url scm)) "/"
                                                        (util/gh-repo (:url scm)) "/"
                                                        scm-rev "/")})))
    (.toString doc)))


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
