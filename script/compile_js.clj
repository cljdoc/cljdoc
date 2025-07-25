#!/usr/bin/env bb

(ns compile-js
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [helper.main :as main]
            [helper.shell :as shell]
            [lread.status-line :as status]
            [pod.babashka.fswatcher :as fw])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def args-usage "Valid args: [--watch|--help]

Options
 --watch        Rebuild client-side assets if they change
 --help         Show this help")

(defn- compile-copy
  "We could use a simple copy but maybe better to use esbuild for consistent console output"
  [{:keys [source-asset-dir source-asset-static-subdir target-dir]}]
  (status/line :head "compile-js: straight copy")
  (shell/command "npx"
                 "--yes"
                 "esbuild"
                 "--minify"
                 "--sourcemap"
                 "--loader:.ico=copy"
                 "--loader:.svg=copy"
                 "--loader:.txt=copy"
                 (str "--outdir=" target-dir)
                 (str (fs/file source-asset-dir source-asset-static-subdir "*.*"))))

(defn- compile-with-hash [{:keys [source-asset-dir target-dir]}]
  (status/line :head "compiling-js: with hash")
  (shell/command "npx"
                 "--yes"
                 "esbuild"
                 "--loader:.svg=copy"
                 "--loader:.png=copy"
                 "--entry-names=[name].[hash]"
                 "--minify"
                 "--sourcemap"
                 (str "--outdir=" target-dir)
                 (str (fs/file source-asset-dir "*.png"))
                 (str (fs/file source-asset-dir "*.css"))
                 (str (fs/file source-asset-dir "*.svg"))))

#_(defn- compile-js [{:keys [js-dir js-entry-point js-out-name target-dir]}]
  (status/line :head "compile-js: transpile TypeScript to JS")
  (shell/command "npx"
                 "--yes"
                 "esbuild"
                 "--target=es2017"
                 "--minify"
                 ;; elasticlunr did not expect to wrapped, expose it like so:
                 "--define:lunr=window.lunr"
                 "--sourcemap"
                 "--entry-names=[name].[hash]"
                 (str "--outdir=" target-dir)
                 (str js-out-name "=" (fs/file js-dir js-entry-point))
                 "--bundle"))

(defn- compile-js [{:keys [js-dir js-entry-point js-out-name target-dir]}]
  (status/line :head "compile-js: transpile Sources to JS")
  ;; to use plugins, it seems we need to use the esbuild API
  (shell/command "node"
                 "esbuild/build.js"
                 js-dir
                 js-entry-point
                 js-out-name
                 target-dir))

(defn- resource-map
  "Map of non-hashed to hashed resource."
  [{:keys [target-dir]}]
  (reduce (fn [acc n]
            (let [f (fs/file-name n)
                  non-hashed-f (str/replace-first f #"\.[A-Z0-9]{8}\." ".")]
              (assoc acc (str "/" non-hashed-f) (str "/" f))))
          {}
          (fs/list-dir target-dir)))

(defn- generate-resource-map [{:keys [manifest-out-dir] :as opts}]
  (status/line :head "compile-js: generate manifest")
  (let [f (fs/file manifest-out-dir "manifest.edn")]
    (with-open [out (io/writer f)]
      (pprint/write (resource-map opts) :stream out))
    (status/line :detail "Wrote: %s" f)))

(defn- compile-all [{:keys [target-dir] :as opts}]
  (fs/delete-tree target-dir)
  (fs/create-dirs target-dir)
  (compile-copy opts)
  (compile-with-hash opts)
  (compile-js opts)
  (generate-resource-map opts)
  (status/line :detail "Completed at %s"
               (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))))

(def ^:private changes-lock (Object.))

(defn- change-detected [{:keys [path]} opts]
  (locking changes-lock
    (status/line :head "Recompiling\nChanged detected in %s" path)
    (compile-all opts)
    (status/line :detail "Watching for changes...")))

(defn- setup-watch-compile [{:keys [source-asset-dir js-dir] :as opts}]
  (let [watch-dirs [source-asset-dir js-dir]]
    (status/line :detail "Watching for changes in... %s" watch-dirs)
    (doseq [d watch-dirs]
      (fw/watch d
                (fn [event]
                  (change-detected event opts))
                {:recursive true}))
    (deref (promise))))

(defn -main [& args]
  (when-let [opts (main/doc-arg-opt args-usage args)]
    (let [compile-opts {:target-dir "resources-compiled/public/out"
                        :manifest-out-dir "resources-compiled" ;; no need for this to be public
                        :source-asset-dir "resources/public"
                        :source-asset-static-subdir "static"
                        :js-dir "js"
                        :js-out-name "cljdoc"
                        :js-entry-point "index.tsx"}]
      (compile-all compile-opts)
      (when (get opts "--watch")
        (setup-watch-compile compile-opts)))))

(main/when-invoked-as-script
 (apply -main *command-line-args*))
