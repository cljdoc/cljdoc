;; This is a 99% copy of https://github.com/clojure-grimoire/lein-grim/blob/master/src/grimoire/doc.clj
;; All credit goes to the authors of that file.
(ns cljdoc.grimoire-helpers
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.namespace.find :as tns.f]
            [grimoire.api]
            [grimoire.api.fs]
            [grimoire.api.fs.write]
            [grimoire.api.fs.read]
            [grimoire.things]
            [grimoire.util]
            [grimoire.either]
            [detritus.var]))

(defn var->type
  "Function from a var to the type of the var.
  - Vars tagged as dynamic or satisfying the .isDynamic predicate are tagged
    as :var values.
  - Vars tagged as macros (as required by the macro contract) are tagged
    as :macro values.
  - Vars with fn? or MultiFn values are tagged as :fn values.
  - All other vars are simply tagged as :var."
  [v]
  {:pre [(var? v)]}
  (let [m (meta v)]
    (cond (:macro m)
          ,,:macro

          (or (:dynamic m)
              (.isDynamic ^clojure.lang.Var v))
          ,,:var

          (or (fn? @v)
              (instance? clojure.lang.MultiFn @v))
          ,,:fn

          :else
          ,,:var)))

(defn var->thing
  "Function from a groupid, artifactid, version, platform and a var to the Def
  Thing representing the given var in the described Artifact."
  [{:keys [groupid artifactid version platform]} var]
  {:pre [(string? groupid)
         (string? artifactid)
         (string? version)
         (string? platform)
         (var? var)]}
  (-> (grimoire.things/->Group    groupid)
      (grimoire.things/->Artifact artifactid)
      (grimoire.things/->Version  version)
      (grimoire.things/->Platform platform)
      (grimoire.things/->Ns       (name (detritus.var/var->ns var)))
      (grimoire.things/->Def      (name (detritus.var/var->sym var)))))

(defn ns->thing
  "Function from a"
  [{:keys [groupid artifactid version platform]} ns-symbol]
  {:pre [(symbol? ns-symbol)
         (string? groupid)
         (string? artifactid)
         (string? version)
         (string? platform)]}
  (-> (grimoire.things/->Group    groupid)
      (grimoire.things/->Artifact artifactid)
      (grimoire.things/->Version  version)
      (grimoire.things/->Platform platform)
      (grimoire.things/->Ns (name ns-symbol))))

(defn var->src
  "Adapted from clojure.repl/source-fn. Returns a string of the source code for
  the given var, if it can find it. Returns nil if it can't find the source.
  Example: (var->src #'clojure.core/filter)"
  [v]
  {:pre [(var? v)]}
  (when-let [filepath (:file (meta v))]
    (when-let [strm (.getResourceAsStream (clojure.lang.RT/baseLoader) filepath)]
      (with-open [rdr (java.io.LineNumberReader. (java.io.InputStreamReader. strm))]
        (binding [*ns* (.ns v)]
          (dotimes [_ (dec (:line (meta v)))]
            (.readLine rdr))
          (let [text (StringBuilder.)
                pbr  (proxy [java.io.PushbackReader] [rdr]
                       (read [] (let [i (proxy-super read)]
                                  (.append text (char i))
                                  i)))]
            (if (= :unknown *read-eval*)
              (throw
               (IllegalStateException.
                "Unable to read source while *read-eval* is :unknown."))
              (read (java.io.PushbackReader. pbr)))
            (str text)))))))

(defn ns-stringifier
  "Function something (either a Namespace instance, a string or a symbol) to a
  string naming the input. Intended for use in computing the logical \"name\" of
  the :ns key which could have any of these values."
  [x]
  (cond (instance? clojure.lang.Namespace x)
        ,,(name (ns-name x))

        (string? x)
        ,,x

        (symbol? x)
        ,,(name x)

        :else
        ,,(throw
           (Exception.
            (str "Don't know how to stringify " x)))))

(defn name-stringifier
  "Function from something (either a symbol, string or something else) which if
  possible computes the logical \"name\" of the input as via clojure.core/name
  otherwise throws an explicit exception."
  [x]
  (cond (symbol? x)
        ,,(name x)

        (string? x)
        ,,x

        :else
        ,,(throw
           (Exception.
            (str "Don't know how to stringify " x)))))

(defn guarded-write-meta
  "Guard around api/write-meta which checks to make sure that there is _not_
  already metadata in the store for the given Thing and issues a warning if
  there already _is_ metadata there without overwriting it."
  [config thing meta]
  (if (and (not (:clobber config))
           (grimoire.either/succeed? (grimoire.api/read-meta (:datastore config) thing)))
    (println
     (format "Warning: metadata for thing %s already exists! continuing w/o clobbering..."
             (grimoire.things/thing->path thing)))
    (do (println (grimoire.things/thing->path thing))
        (grimoire.api/write-meta (:datastore config) thing meta))))

(defn write-docs-for-var
  "General case of writing documentation for a Var instance with
  metadata. Compute a \"docs\" structure from the var's metadata and then punt
  off to write-meta which does the heavy lifting."
  [config var]
  (let [docs (-> (meta var)
                 (assoc  :src  (var->src var)
                         :type (var->type var))
                 (update :name #(name-stringifier (or %1 (grimoire.things/thing->name var))))
                 (update :ns   #(ns-stringifier (or %1 (grimoire.things/thing->name (grimoire.things/thing->namespace var)))))
                 (dissoc :inline
                         :protocol
                         :inline
                         :inline-arities))]
    (assert (:name docs) "Var name was nil!")
    (assert (:ns docs) "Var namespace was nil!")
    (guarded-write-meta config
                        (var->thing config var)
                        docs)))

(def var-blacklist
  #{#'clojure.data/Diff})

(defn write-docs-for-ns
  "Function of a configuration and a Namespace which traverses the public vars
  of that namespace, writing documentation for each var as specified by the
  config.
  FIXME: currently provides special handling for the case of documenting
  clojure.core, so that \"special forms\" in core will be documented via the
  write-docs-for-specials function. This behavior will change and be replaced
  with fully fledged support for writing documentation for arbitrary non-def
  symbols via an input datastructure."
  [config ns]
  (let [ns-vars (->> (ns-publics ns) vals (remove var-blacklist))
        macros  (filter detritus.var/macro? ns-vars)
        fns     (filter #(and (fn? @%1)
                              (not (detritus.var/macro? %1)))
                        ns-vars)
        vars    (filter #(not (fn? @%1)) ns-vars)
        ns-meta (-> ns the-ns meta (or {}))]

    (when-not (:skip-wiki ns-meta)
      ;; Respect ^:skip-wiki from clojure-grimoire/lein-grim#4

      (let [thing (ns->thing config ns)]
        (grimoire.api/write-meta (:datastore config) thing ns-meta))

      ;; write per symbol docs
      (doseq [var ns-vars]
        (write-docs-for-var config var)))

    ;; FIXME: should be a real logging thing
    (println "Finished" ns)
    nil))

(defn build-grim [groupid artifactid version dst]
  (let [;; _        (assert ?platform "Platform missing!")
        platform (grimoire.util/normalize-platform :clj #_?platform)
        _        (assert platform "Unknown platform!")
        _        (assert groupid "Groupid missing!")
        _        (assert artifactid "Artifactid missing!")
        _        (assert version "Version missing!")
        _        (assert dst "Doc target dir missing!")
        config   {:groupid    groupid
                  :artifactid artifactid
                  :version    version
                  :platform   platform
                  :datastore  (grimoire.api.fs/->Config dst "" "")
                  :clobber    true}
        pattern  (format ".*?/%s/%s/%s.*"
                         (clojure.string/replace groupid "." "/")
                         artifactid
                         version)
        pattern  (re-pattern pattern)]

    ;; write placeholder meta
    ;;----------------------------------------
    (reduce (fn [acc f]
              (grimoire.api/write-meta (:datastore config) acc {})
              (f acc))
            (grimoire.things/->Group groupid)
            [#(grimoire.things/->Artifact % artifactid)
             #(grimoire.things/->Version % version)
             #(grimoire.things/->Platform % platform)
             identity])

    (doseq [e (cp/classpath)]
      (if (re-matches pattern (str e))
        (doseq [ns (tns.f/find-namespaces [e])]
          (when-not (= ns 'clojure.parallel) ;; FIXME: get out nobody likes you
            (require ns)
            (write-docs-for-ns config ns)))
        (println "Did not find" pattern "in\n" (str e))))

    #_(when ?special-file
      (write-docs-for-specials config ?special-file))))

(comment
  (build-grim "sparkledriver" "sparkledriver" "0.2.2"
              "gggrim")

  )
