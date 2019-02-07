(ns cljdoc.git-repo
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [digest :as digest])
  (:import  (org.eclipse.jgit.lib RepositoryBuilder
                                  Repository
                                  ObjectIdRef$PeeledNonTag
                                  ObjectIdRef$PeeledTag
                                  ObjectIdRef$Unpeeled
                                  ObjectLoader
                                  FileMode
                                  Constants)
            (org.eclipse.jgit.revwalk RevWalk)
            (org.eclipse.jgit.treewalk TreeWalk)
            (org.eclipse.jgit.treewalk.filter PathFilter)
            (org.eclipse.jgit.api Git TransportConfigCallback LsRemoteCommand)
            (org.eclipse.jgit.transport SshTransport JschConfigSessionFactory)
            (com.jcraft.jsch JSch)
            (com.jcraft.jsch.agentproxy Connector ConnectorFactory RemoteIdentityRepository)))

(def jsch-session-factory
  "A session-factory for use with JGit to access private
  repositories using SSH private key authentication.

  This will look at `~/.ssh/config` to find the right
  private key to use or default to `~/.ssh/id_rsa`.

  Keys MUST BE IN PEM FORMAT (ssh-keygen -m PEM) or the
  authentication will fail with something like
  \" invalid privatekey: [B@370d39ee\".

  Read https://security.stackexchange.com/questions/143114 for details."
  (proxy [JschConfigSessionFactory] []
    (configure [host session]
      (.setConfig session  "StrictHostKeyChecking" "no"))
    ;; This could be used to specify keys explicitly
    #_(createDefaultJSch [fs]
      (doto (proxy-super createDefaultJSch fs)
        (.addIdentity "/Users/martinklepsch/.ssh/martinklepsch-lambdawerk2")))))

(defn clonable?
  "A rough heuristic to evaluate whether a repository can be cloned.

  This currently only really checks HTTP protocol remotes, for proper SSH support we'd need to
  also call `setTransportConfigCallback` similar as it's done in [[clone]]."
  [uri]
  (let [lscmd (LsRemoteCommand. nil)]
    (. lscmd (setRemote uri))
    (try (do (.call lscmd) true)
         (catch Exception _ false))))

(defn clone [uri target-dir]
  (.. Git
      cloneRepository
      (setURI uri)
      (setCloneAllBranches true)
      (setDirectory target-dir)
      (setTransportConfigCallback
       (reify TransportConfigCallback
         (configure [_ transport]
           (when (instance? SshTransport transport)
             (.setSshSessionFactory ^SshTransport transport jsch-session-factory)))))
      call))

(defn read-origin
  [^Git git-repo]
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

(defn exists? [^Git g rev]
  (some? (.resolve (.getRepository g) rev)))

(defn- tree-for
  [g rev]
  {:pre [(string? rev)]}
  (let [repo        (.getRepository g)
        last-commit (.resolve repo rev)]
    (if last-commit
      (-> (RevWalk. repo)
          (.parseCommit last-commit)
          (.getTree))
      (let [origin (read-origin g)]
        (throw (ex-info (format "Could not find revision %s in repo %s" rev origin)
                        {:rev rev :origin origin}))))))

(defn slurp-file-at
  "Read a file `f` in the Git repository `g` at revision `rev`.

   If the file cannot be found, return `nil`."
  [^Git g rev f]
  (if-some [tree (tree-for g rev)]
    (let [repo      (.getRepository g)
          tree-walk (TreeWalk/forPath repo f tree)]
      (when tree-walk
        (slurp (.openStream (.open repo (.getObjectId tree-walk 0))))))
    (log/warnf "Could not resolve revision %s in repo %s" rev g)))

(s/def ::git #(instance? Git %))
(s/def ::path string?)
(s/def ::object-loader #(instance? ObjectLoader %))
(s/def ::git-object (s/keys :req-un [::path ::object-loader]))

(s/fdef ls-files
  :args (s/cat :repository ::git :revision string?)
  :ret (s/coll-of ::git-object))

(defn ls-files
  "Return a seq of maps {:path 'path-of-file :obj-loader 'ObjectLoader}
  for files in the git repository at the given revision `rev`.
  ObjectLoader instances can be consumed with slurp, input-stream, etc.

  Files in submodules are skipped."
  [^Git g rev]
  (let [tree (tree-for g rev)
        repo (.getRepository g)
        tw   (TreeWalk. repo)]
    (.addTree tw tree)
    (.setRecursive tw true)
    (loop [files []]
      (if (.next tw)
        (recur
         (if (= FileMode/GITLINK (.getFileMode tw))
           files ; Submodule reference, skip
           (conj files {:path          (.getPathString tw)
                        :object-loader (.open repo (.getObjectId tw 0))})))
        files))))

(s/fdef path-sha-pairs
  :args (s/cat :git-objects (s/coll-of ::git-object))
  :ret (s/map-of string? string?))

(defn path-sha-pairs [files]
  (->> (for [{:keys [path object-loader]} files]
         [path (digest/sha-256 (io/input-stream object-loader))])
       (into {})))

(extend ObjectLoader
  io/IOFactory
  (assoc io/default-streams-impl
         :make-input-stream (fn [x opts] (.openStream x))
         :make-reader (fn [x opts] (io/reader (.openStream x)))))

(defn read-cljdoc-config
  [repo rev]
  {:pre [(some? repo) (string? rev) (seq rev)]}
  (or (cljdoc.git-repo/slurp-file-at repo rev "doc/cljdoc.edn")
      (cljdoc.git-repo/slurp-file-at repo rev "docs/cljdoc.edn")))

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
  (def r (->repo (io/file "/Users/martin/code/02-oss/workflo-macros")))
  (slurp-file-at r "master" "bidi.cljc")
  (find-filepath-in-repo r "master" "project.clj")
  (find-filepath-in-repo r "master" "bidi.cljc")

  (require 'clojure.spec.test.alpha)
  (clojure.spec.test.alpha/instrument)

  (def workflo-macros-files (ls-files (->repo (io/file "/Users/martin/code/02-oss/workflo-macros")) "master"))
  (def yada-files (ls-files (->repo (io/file "/Users/martin/code/02-oss/yada")) "master"))
  (def manifold-files (ls-files (->repo (io/file "/Users/martin/code/02-oss/manifold")) "master"))

  (s/check-asserts?)

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
