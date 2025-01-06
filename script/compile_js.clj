#!/usr/bin/env bb

(ns compile-js
  (:require [babashka.fs :as fs]
            [helper.main :as main]
            [helper.shell :as shell]
            [lread.status-line :as status]
            [pod.babashka.fswatcher :as fw]))

(def args-usage "Valid args: [--watch|--help]

Options
 --watch        Rebuild client-side assets if they change
 --help         Show this help")

(defn- compile-static
  "We could use a simple copy but maybe better to use esbuild for consistent console output"
  [{:keys [source-asset-dir source-asset-static-subdir target-dir]}]
  (status/line :head "copying static resources")
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

(defn- compile-js [{:keys [js-dir js-entry-point js-out-name target-dir]}]
  (status/line :head "compiling to JavaScript")
  (shell/command "npx"
                 "--yes"
                 "esbuild"
                 "--target=es2022"
                 "--minify"
                 "--sourcemap"
                 "--entry-names=[name]-[hash]"
                 (str "--outdir=" target-dir)
                 (str js-out-name "=" (fs/file js-dir js-entry-point))
                 "--bundle"))

(defn- compile-dynamic [{:keys [source-asset-dir target-dir]}]
  (status/line :head "compiling dynamic resources")
  (shell/command "npx"
                 "--yes"
                 "esbuild"
                 "--minify"
                 "--loader:.svg=copy"
                 "--loader:.png=copy"
                 "--entry-names=[name]-[hash]"
                 (str "--outdir=" target-dir)
                 (str (fs/file source-asset-dir "*.png"))
                 (str (fs/file source-asset-dir "*.css"))
                 (str (fs/file source-asset-dir "*.svg"))))

(defn- compile-all [{:keys [target-dir] :as opts}]
  (fs/delete-tree target-dir)
  (fs/create-dir target-dir)
  (compile-static opts)
  (compile-dynamic opts)
  (compile-js opts))

(def ^:private changes-lock (Object.))

(defn- change-detected [{:keys [path]} opts]
  (locking changes-lock
    (status/line :detail "Changed detected in %s" path)
    (compile-all opts)))

(defn- setup-watch-compile [{:keys [source-asset-dir js-dir] :as opts}]
  (status/line :detail "Watching for changes...")
  (doseq [d [source-asset-dir js-dir]]
    (fw/watch d
              (fn [event]
                (change-detected event opts))
              {:recursive true}))
  (deref (promise)))

(defn -main [& args]
  (when-let [opts (main/doc-arg-opt args-usage args)]
    (let [compile-opts {:target-dir "resources-compiled/public/out"
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
