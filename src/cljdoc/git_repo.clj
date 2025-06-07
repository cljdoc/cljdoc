(ns cljdoc.git-repo
  (:require [clj-commons.digest :as digest]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import  (com.jcraft.jsch Session)
            (org.eclipse.jgit.api Git LsRemoteCommand TransportConfigCallback)
            (org.eclipse.jgit.lib FileMode ObjectId ObjectIdRef$PeeledNonTag ObjectIdRef$PeeledTag ObjectLoader Ref Repository RepositoryBuilder)
            (org.eclipse.jgit.revwalk RevCommit RevTree RevWalk)
            (org.eclipse.jgit.transport RemoteConfig SshTransport URIish)
            (org.eclipse.jgit.transport.ssh.jsch JschConfigSessionFactory)
            (org.eclipse.jgit.treewalk TreeWalk)
            (org.eclipse.jgit.treewalk.filter AndTreeFilter PathFilter TreeFilter)))

(set! *warn-on-reflection* true)

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
    (configure [host ^Session session]
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
    (try (.call lscmd) true
         (catch Exception _ false))))

(defn clone [uri target-dir]
  (-> (Git/cloneRepository)
      (.setURI uri)
      (.setCloneAllBranches true)
      (.setDirectory target-dir)
      (.setTransportConfigCallback
       (reify TransportConfigCallback
         (configure [_ transport]
           (when (instance? SshTransport transport)
             (.setSshSessionFactory ^SshTransport transport jsch-session-factory)))))
      .call))

(defn read-origin
  [^Git git-repo]
  (let [remotes (->> git-repo
                     .remoteList
                     .call
                     (map (fn [^RemoteConfig remote]
                            [(keyword (.getName remote))
                             (-> remote
                                 .getURIs
                                 ^URIish first
                                 .toString)]))
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

(defn ->repo
  "Opens an AutoCloseable repo, it is caller's responsibility to close."
  ^Git [^java.io.File d]
  {:pre [(some? d) (.isDirectory d)]}
  (-> (RepositoryBuilder.)
      (.setGitDir (io/file d ".git"))
      (.readEnvironment)
      (.findGitDir)
      (.build)
      (Git.)))

(defn default-branch [^Git repo]
  (.getBranch (.getRepository repo)))

(defn find-tag ^Ref [^Git repo tag-str]
  (->> repo
       .tagList
       .call
       (filter (fn [^Ref t]
                 (= (.getName t)
                    (str "refs/tags/" tag-str))))
       first))

(defn version-tag
  ([^Git g version-str]
   (version-tag g "" version-str))
  ([^Git g prefix version-str]
   (when-let [tag-obj (or (find-tag g (str prefix version-str))
                          (find-tag g (str prefix "v" version-str)))]
     {:name (-> tag-obj
                .getName
                (string/replace #"^refs/tags/" ""))
      :sha  (-> tag-obj .getObjectId .getName)
      :commit (condp instance? tag-obj
                ;; Not sure I really understand the difference between these two
                ;; PeeledTags seem to have their own sha while PeeledNonTags dont
                ObjectIdRef$PeeledTag    (-> tag-obj .getPeeledObjectId .getName)
                ObjectIdRef$PeeledNonTag (-> tag-obj .getObjectId .getName))})))

(defn- tree-for
  ^RevTree [^Git g rev]
  {:pre [(string? rev)]}
  (let [repo        (.getRepository g)
        last-commit (.resolve repo rev)]
    (if last-commit
      (with-open [rev-walk (RevWalk. repo)]
        (-> rev-walk
            (.parseCommit last-commit)
            (.getTree)))
      (let [origin (read-origin g)]
        (throw (ex-info (format "Could not find revision %s in repo %s" rev origin)
                        {:rev rev :origin origin}))))))

(defn slurp-file-at
  "Read a file `f` in the Git repository `g` at revision `rev`.

   If the file cannot be found, return `nil`."
  [^Git g rev ^String f]
  (if-some [tree (tree-for g rev)]
    (let [^Repository repo (.getRepository g)
          tree-walk-maybe (TreeWalk/forPath repo f tree)]
      (when tree-walk-maybe
        (with-open [tree-walk tree-walk-maybe]
          (slurp (.openStream (.open repo (.getObjectId tree-walk 0)))))))
    (log/warnf "Could not resolve revision %s in repo %s" rev g)))

(defn get-contributors
  "Get a list of contributors to a given file ordered by the number
  of commits they have made to the given file `f`."
  [^Git g rev f]
  (let [repo (.getRepository g)]
    (with-open [revwalk (RevWalk. repo)]
      (.markStart revwalk (.parseCommit revwalk (.resolve repo rev)))
      (.setTreeFilter revwalk
                      (AndTreeFilter/create (PathFilter/create f)
                                            TreeFilter/ANY_DIFF))
      (->> (map (fn [^RevCommit rev-commit] (-> rev-commit .getAuthorIdent .getName))
                (iterator-seq (.iterator revwalk)))
           frequencies
           (sort-by val >)
           (map key)))))

(defn file-sha-map
  "Return a map of file to 256 sha
  Files in submodules are skipped."
  [^Git g rev]
  (let [tree (tree-for g rev)
        repo (.getRepository g)]
    (with-open [tw (TreeWalk. repo)]
      (.addTree tw tree)
      (.setRecursive tw true)
      (loop [files {}]
        (if (.next tw)
          (recur
           (if (= FileMode/GITLINK (.getFileMode tw))
             files ; Submodule reference, skip
             (assoc files (.getPathString tw)
                    (with-open [stream (io/input-stream (.open repo (.getObjectId tw 0)))]
                      (digest/sha-256 stream)))))
          files)))))

(extend ObjectLoader
  io/IOFactory
  (assoc io/default-streams-impl
         :make-input-stream (fn [^ObjectLoader x _opts] (.openStream x))
         :make-reader (fn [^ObjectLoader x _opts] (io/reader (.openStream x)))))

(defn read-cljdoc-config
  [repo rev]
  {:pre [(some? repo) (string? rev) (seq rev)]}
  (or (slurp-file-at repo rev "doc/cljdoc.edn")
      (slurp-file-at repo rev "docs/cljdoc.edn")))

(defn ls-remote-sha
  "Return git sha for remote tag"
  [^String uri tag]
  (some-> (LsRemoteCommand. nil)
          (.setHeads false)
          (.setTags true)
          (.setRemote uri)
          (.callAsMap)
          ^Ref (get (str "refs/tags/" tag) nil)
          (.getObjectId)
          (ObjectId/toString)))

(comment
  (require '[clj-memory-meter.core :as mm])

  (mm/measure (file-sha-pairs (->repo (io/file "/home/lee/proj/oss/-verify/clojure-desktop-toolkit")) "v0.2.2"))
  ;; => "746.1 KiB"

  (ls-remote-sha "https://github.com/cljdoc/cljdoc-analyzer.git" "RELEASE")
  (ls-remote-sha "git@github.com:cljdoc/cljdoc-analyzer.git" "RELEASE")

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

  (read-file-at r (.getName (find-tag r "1.2.0"))))
