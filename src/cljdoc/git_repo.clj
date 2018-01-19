(ns cljdoc.git-repo
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import  [org.eclipse.jgit.lib RepositoryBuilder]
            [org.eclipse.jgit.api Git]))

(defn clone [uri target-dir]
  (printf "Cloning repo %s\n" uri)
  (.. Git
      cloneRepository
      (setURI uri)
      (setDirectory target-dir)
      call))

(defn read-origin
  [^Git git-repo]
  {:post [(.startsWith % "https://")
          (not (.endsWith % ".git"))]}
  (->> (.. git-repo remoteList call)
       (map (fn [remote]
              [(keyword (.getName remote))
               (.toString (first (.getURIs remote)))]))
       (into {})
       :origin))

(defn ->repo [^java.io.File d]
  {:pre [(.isDirectory d)]}
  (-> (RepositoryBuilder.)
      (.setGitDir (io/file d ".git"))
      (.readEnvironment)
      (.findGitDir)
      (.build)
      (Git.)))

(defn git-checkout-repo [^Git repo rev]
  (printf "Checking out revision %s\n" rev)
  (.. repo checkout (setName rev) call))

(defn assert-first [[x & rest :as xs]]
  (if (empty? rest)
    x
    (throw (ex-info "Expected collection with one item, got multiple"
                    {:coll xs}))))

(defn find-tag [^Git repo tag-str]
  (->> (.. repo tagList call)
       (filter (fn [t]
                 (= (.getName t)
                    (str "refs/tags/" tag-str))))
       assert-first))

(defn git-tag-names [repo]
  (->> repo
       (.tagList)
       (.call)
       (map #(->> % .getName (re-matches #"refs/tags/(.*)") second))))

(defn read-repo-meta [^Git repo version-str]
  (let [tag-obj (or (find-tag repo version-str)
                    (find-tag repo (str "v" version-str)))]
    {:scm-url (read-origin repo)
     :sha     (.. tag-obj getObjectId getName)
     :tag     (-> (.. tag-obj getName)
                  (string/replace #"^refs/tags/" ""))}))

(defn patch-level-info
  ;; Non API documentation should be updated with new Git revisions,
  ;; not only once tagged releases are published to Clojars
  ;; Some versioning scheme is required for this Non-API documentation
  ;; After recent discussion with @arrdem I'm thinking the following
  ;; version identifier might be best:
  ;;
  ;;     {:version "2.0.0" :patch-level 2}
  ;;
  ;; Where this means two changes have been made since the version
  ;; 2.0.0 has been tagged. Whether the patch-level is increased
  ;; with every commit or only with commits that modified the resulting
  ;; doc-bundle is to be decided.
  ;; TODO probably this should be captured in an ADR
  [^Git repo]

  ;; Seems like we need to walk the git commits here to retrieve tags in order
  ;; https://stackoverflow.com/questions/31836087/list-all-tags-in-current-branch-with-jgit
  )

(defn write-meta-for-version [store version-thing])

(comment
  (def r (->repo (io/file "target/git-repo")))

  (->> (.. r tagList call)
       (map #(println (.getName %)))
       )

  (.getName (.getObjectId (find-tag r "2.1.3")))

  (read-repo-meta r "2.1.3")

  (read-origin r)
  (list-tags r)

  (->> (.. r tagList call)
       (map (fn [t] [(.getName t) (.getObjectId t)]))
       (into {})
       clojure.pprint/pprint)




  )

