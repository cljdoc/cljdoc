(ns cljdoc.util.fixref
  "Utilities to fix broken references in HTML that was intended to be
  rendered in other places, e.g. GitHub."
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

(defn- gh-owner [gh-url]
  (second (re-find #"^https*://github.com/(\w+)/" gh-url)))

(defn- gh-repo [gh-url]
  (second (re-find #"^https*://github.com/\w+/(\w+)" gh-url)))

(defn fix-link
  [href {:keys [git-ls scm doctree artifact-entity]}]
  (if (.startsWith href "/")
    (str (:url scm) "/blob/" (:sha scm) href)
    href)) ;FIXME

(defn fix-image
  [src {:keys [git-ls scm doctree artifact-entity]}]
  (if (.startsWith src "/")
    (str "https://raw.githubusercontent.com/"
         (gh-owner (:url scm)) "/"
         (gh-repo (:url scm)) "/"
         (:sha scm) src)
    src)) ;FIXME

(defn fix
  [file-path html-str {:keys [git-ls scm doctree artifact-entity] :as fix-opts}]
  (let [doc (Jsoup/parse html-str)]
    (doseq [broken-link (->> (.select doc "a")
                             (map #(.attributes %))
                             (remove #(absolute-uri? (.get % "href"))))]
      (.put broken-link "href" (fix-link (.get broken-link "href") fix-opts)))

    (doseq [broken-img (->> (.select doc "img")
                            (map #(.attributes %))
                            (remove #(absolute-uri? (.get % "src"))))]
      (.put broken-img "src" (fix-image (.get broken-img "src") fix-opts)))
    (.toString doc)))

(comment
  (require '[cljdoc.renderers.markup :as md])

  (defn doc []
    (Jsoup/parse
     (md/markdown-to-html (slurp "https://raw.githubusercontent.com/metosin/jsonista/master/README.md"))))

  (def fix-opts
    {:git-ls []
     :scm {:url "https://github.com/metosin/jsonista"
           :sha "07c59d1eadd458534c81d6ef8251b5fd5d754a74"}})

  (fix "README.md"
       (md/markdown-to-html (slurp "https://raw.githubusercontent.com/metosin/jsonista/master/README.md"))
       fix-opts)

  (->> (.select (doc) "img")
       (map #(.attributes %))
       (remove #(or (.startsWith (.get % "src") "http://")
                    (.startsWith (.get % "src") "https://")))
       (map #(doto % (.put "src" (fix-image (.get % "src") fix-opts)))))

  (.get (.attributes (first (.select doc "a"))) "href")

  )
