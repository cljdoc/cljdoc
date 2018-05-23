(ns cljdoc.git-repo
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [digest :as digest])
  (:import  (org.eclipse.jgit.lib RepositoryBuilder
                                  Repository
                                  ObjectIdRef$PeeledNonTag
                                  ObjectIdRef$PeeledTag
                                  ObjectIdRef$Unpeeled
                                  Constants)
            (org.eclipse.jgit.revwalk RevWalk)
            (org.eclipse.jgit.treewalk TreeWalk)
            (org.eclipse.jgit.treewalk.filter PathFilter)
            (org.eclipse.jgit.api Git TransportConfigCallback)
            (org.eclipse.jgit.transport SshTransport JschConfigSessionFactory)
            (com.jcraft.jsch JSch)
            (com.jcraft.jsch.agentproxy Connector ConnectorFactory RemoteIdentityRepository)))

(def ^:private ^TransportConfigCallback ssh-callback
  (delay
    (let [factory (doto (ConnectorFactory/getDefault) (.setPreferredUSocketFactories "jna,nc"))
          connector (.createConnector factory)]
      (JSch/setConfig "PreferredAuthentications" "publickey")
      (reify TransportConfigCallback
        (configure [_ transport]
          (.setSshSessionFactory ^SshTransport transport
            (proxy [JschConfigSessionFactory] []
              (configure [host session])
              (getJSch [hc fs]
                (doto (proxy-super getJSch hc fs)
                  (.setIdentityRepository (RemoteIdentityRepository. connector)))))))))))

(defn clone [uri target-dir]
  (printf "Cloning repo %s\n" uri)
  (.. Git
      cloneRepository
      (setURI uri)
      (setDirectory target-dir)
      ;; This breaks if the URI uses http
      ;; (setTransportConfigCallback @ssh-callback)
      call))

(defn read-origin
  [^Git git-repo]
  {:post [(.startsWith % "https://")]}
  (let [remotes (->> (.. git-repo remoteList call)
                     (map (fn [remote]
                            [(keyword (.getName remote))
                             (.toString (first (.getURIs remote)))]))
                     (into {}))]
    ;; TODO for some reason on Circle CI the ssh:// protocol is used
    ;; despite us explicitly providing the http URI. Probably we should
    ;; extend this to return a map like:
    ;; {:github "some/thing"}
    ;; This would also make things more extensible for stuff like bitbucket
    ;; although admittedly I don't care about that much at this point
    (-> (:origin remotes)
        (string/replace #"^git@github.com:" "https://github.com/")
        (string/replace #"^ssh://git@" "https://"))))

(defn ->repo [^java.io.File d]
  {:pre [(some? d) (.isDirectory d)]}
  (-> (RepositoryBuilder.)
      (.setGitDir (io/file d ".git"))
      (.readEnvironment)
      (.findGitDir)
      (.build)
      (Git.)))

(defn git-checkout-repo [^Git repo rev]
  (log/infof "Checking out revision %s\n" rev)
  (.. repo checkout (setName rev) call))

(defn find-tag [^Git repo tag-str]
  (->> (.. repo tagList call)
       (filter (fn [t]
                 (= (.getName t)
                    (str "refs/tags/" tag-str))))
       first))

(defn version-tag [^Git g version-str]
  (when-let [tag-obj (or (find-tag g version-str)
                         (find-tag g (str "v" version-str)))]
    {:name (-> (.. tag-obj getName)
               (string/replace #"^refs/tags/" ""))
     :sha  (.. tag-obj getObjectId getName)
     :commit (condp instance? tag-obj
               ;; Not sure I really understand the difference between these two
               ;; PeeledTags seem to have their own sha while PeeledNonTags dont
               ObjectIdRef$PeeledTag    (.. tag-obj getPeeledObjectId getName)
               ObjectIdRef$PeeledNonTag (.. tag-obj getObjectId getName))}))

(defn git-tag-names [repo]
  (->> repo
       (.tagList)
       (.call)
       (map #(->> % .getName (re-matches #"refs/tags/(.*)") second))))

(defn- tree-for
  [g rev]
  (let [repo        (.getRepository g)
        last-commit (.resolve repo rev)]
    (when last-commit
      (-> (RevWalk. repo)
          (.parseCommit last-commit)
          (.getTree)))))

(defn slurp-file-at
  "Read a file `f` in the Git repository `g` at revision `rev`.

  If the file cannot be found, return `nil`."
  [^Git g rev f]
  (if-let [tree (tree-for g rev)]
    (let [repo      (.getRepository g)
          tree-walk (TreeWalk/forPath repo f tree)]
      (when tree-walk
        (slurp (.openStream (.open repo (.getObjectId tree-walk 0))))))
    (log/warnf "Could not resolve revision %s in repo %s" rev g)))

(defn ls-files
  "Return a map of all filepaths and there SHA256
  in the git repository at the given revision `rev`."
  [^Git g rev]
  (let [tree (tree-for g rev)
        repo (.getRepository g)
        tw   (TreeWalk. repo)]
    (.addTree tw tree)
    (.setRecursive tw true)
    (loop [files {}]
      (if (.next tw)
        (recur (assoc files (.getPathString tw)
                            (digest/sha-256
                             (.openStream
                              (.open repo (.getObjectId tw 0))))))
        files))))

(defn read-cljdoc-config
  [repo rev]
  {:pre [(some? repo) (string? rev) (seq rev)]}
  (cljdoc.git-repo/slurp-file-at repo rev "doc/cljdoc.edn"))

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

(comment
  (def r (->repo (io/file "data/git-repos/fulcrologic/fulcro/2.5.4/")))

  (def r (->repo (io/file "/Users/martin/code/02-oss/bidi")))
  (slurp-file-at r "master" "bidi.cljc")
  (find-filepath-in-repo r "master" "project.clj")
  (find-filepath-in-repo r "master" "bidi.cljc")

  (clojure.pprint/pprint
   (ls-files r "master"))

  (let [t (.getTree (.parseCommit (RevWalk. (.getRepository r))
                                  (.resolve (.getRepository r) "master")))
        tw (TreeWalk/forPath (.getRepository r) "project.clj" t)]
    (prn '(.getObjectId tw 0) (.getObjectId tw 0))
    (prn  '(.next tw) (.next tw))
    (slurp (.openStream
            (.open (.getRepository r)
                   (.getObjectId tw 0)))))

  (version-tag r "1.2.10")
  (git-checkout-repo r (.getName (version-tag r "1.2.10")))
  (slurp-file-at r "master" "doc/cljdoc.edn")

  (.resolve (.getRepository r) "master")

  (read-cljdoc-config (->repo (io/file "/Users/martin/code/02-oss/reitit")) "0.1.0")

  (.getPeeledObjectId -t)

  (read-origin r)
  (find-tag r "0.1.7-alpha5")

  (clone "https://github.com/fulcrologic/fulcro.git" (io/file "fulcro-git"))

  (.getName (find-tag r "1.2.0"))

  (read-file-at r (.getName (find-tag r "1.2.0")))
  )
