(ns cljdoc.util.scm
  "Utilities to extract information from SCM urls (GitHub et al)"
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [lambdaisland.uri :as uri]))

(defn owner [scm-url]
  (get (string/split (:path (uri/uri scm-url)) #"/") 1))

(defn repo [scm-url]
  (get (string/split (:path (uri/uri scm-url)) #"/") 2))

(defn coordinate [scm-url]
  (->> (string/split (:path (uri/uri scm-url)) #"/")
       (filter seq)
       (string/join "/")))

(defn provider [scm-url]
  (when-some [host (:host (uri/uri scm-url))]
    (cond
      (.endsWith host "github.com") :github
      (.endsWith host "gitlab.com") :gitlab
      (.endsWith host "sr.ht") :sourcehut
      (.endsWith host "codeberg.org") :codeberg
      (.endsWith host "bitbucket.org") :bitbucket
      (.startsWith host "gitea.") :gitea)))

(defn icon-url
  "Return url to icon for `scm-url`.
  `:asset-prefix` supports online (default) and offline rendering."
  ([scm-url]
   (icon-url scm-url {:asset-prefix "/"}))
  ([scm-url {:keys [asset-prefix]}]
   (let [provider (provider scm-url)]
     (case provider
       :github "https://microicon-clone.vercel.app/github"
       :gitlab "https://microicon-clone.vercel.app/gitlab"
       :sourcehut  (str asset-prefix "sourcehut.svg")
       :codeberg (str asset-prefix "codeberg.svg")
       :bitbucket "https://microicon-clone.vercel.app/bitbucket"
       :gitea "https://microicon-clone.vercel.app/gitea"
       "https://microicon-clone.vercel.app/git"))))

(defn- url-scheme
  "Some providers share a url scheme, for example :gitlab uses the same scheme as :github, and :codeberg is hosted on :gitea."
  [scm-url]
  (let [provider (provider scm-url)]
    (case provider
      :codeberg :gitea
      :gitlab :github
      provider)))

(defn http-uri
  "Given a URI pointing to a git remote, normalize that URI to an HTTP one."
  [scm-url]
  (cond
    (.startsWith scm-url "http")
    scm-url

    (or (.startsWith scm-url "git@")
        (.startsWith scm-url "ssh://"))
    (-> scm-url
        (string/replace #":" "/")
        (string/replace #"\.git$" "")
        ;; three slashes because of prior :/ replace
        (string/replace #"^(ssh///)*git@" "http://"))))

(defn fs-uri
  "Normalize a provided `scm-path` to it's canonical path,
  return `nil` if the path does not exist on the filesystem."
  [scm-path]
  (let [f (io/file scm-path)]
    (when (.exists f)
      (.getCanonicalPath f))))

(defn ssh-uri
  "Given a URI pointing to a git remote, normalize that URI to an SSH one."
  [scm-url]
  (cond
    (.startsWith scm-url "git@")
    scm-url

    (.startsWith scm-url "http")
    (let [{:keys [host path]} (uri/uri scm-url)]
      (str "git@" host ":" (subs path 1) ".git"))))

(defn- scm-rev [{:keys [tag commit] :as _scm}]
  (or (:name tag) commit))

(defn rev-formatted-base-url
  "Return base url appropriate for `scm` for SCM formatted content at revision specifed by `tag` or `commit`.
  Favors `tag` over `commit`."
  [{:keys [url tag commit] :as scm}]
  (let [scheme (url-scheme url)]
    (if (= :gitea scheme)
      (if (:name tag)
        (str url "/src/tag/" (:name tag) "/")
        (str url "/src/commit/" commit "/"))
      (let [rev (scm-rev scm)]
        (case scheme
          :sourcehut (str url "/tree/" rev "/")
          :bitbucket (str url "/src/" rev "/")
          (str url "/blob/" rev "/"))))))

(defn line-anchor [{:keys [url] :as _scm}]
  (if (= :bitbucket (provider url))
    "#lines-"
    "#L"))

(defn rev-raw-base-url
  "Return base url approprite for `scm` for SCM unformatted (raw) content at revision specified by `tag` or `commit`.
  Favors `tag` over `commit`"
  [{:keys [url tag commit] :as scm}]
  (let [scheme (url-scheme url)]
    (if (= :gitea scheme)
      (if (:name tag)
        (str url "/raw/tag/" (:name tag) "/")
        (str url "/raw/commit/" commit "/"))
      (let [rev (scm-rev scm)]
        (if (= :sourcehut scheme)
          (str url "/blob/" rev "/")
          (str url "/raw/" rev "/"))))))

(defn branch-url
  "Return url for `source-file` for scm `url` on scm `branch`."
  [{:keys [url branch] :as _scm} source-file]
  (let [scheme (url-scheme url)
        blob-path (cond
                    (= scheme :sourcehut) "/tree/"
                    (= scheme :gitea) "/src/branch/"
                    (= scheme :bitbucket) "/src/"
                    :else "/blob/")]
    (str url blob-path (or branch "master") "/" source-file)))

(defn normalize-git-url
  "Ensure that the passed string is a git URL and that it's using HTTPS"
  [s]
  (cond-> s
    (.startsWith s "http") (string/replace #"^http://" "https://")
    (.startsWith s "git@github.com:") (string/replace #"^git@github.com:" "https://github.com/")
    (.endsWith s ".git") (string/replace #".git$" "")))
