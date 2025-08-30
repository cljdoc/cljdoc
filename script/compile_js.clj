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

(defn- cmd
  "Override default behaviour to support continueing on failure while in watch mode"
  [cmd & args]
  (apply shell/command
         {:error-fn (fn throw-on-error [{{:keys [exit cmd]} :proc}]
                      (throw (ex-info (format "shell exited with %d for: %s"
                                              exit (with-out-str (pprint/pprint cmd))) {})))}
         cmd args))

(defn- compile-copy
  "We could use a simple copy but maybe better to use esbuild for consistent console output"
  [{:keys [source-asset-dir source-asset-static-subdir target-dir]}]
  (status/line :head "compile-js: straight copy")
  (cmd "npx"
       "--yes"
       "esbuild"
       "--minify"
       "--sourcemap"
       "--loader:.svg=copy"
       "--loader:.txt=copy"
       (str "--outdir=" target-dir)
       (str (fs/file source-asset-dir source-asset-static-subdir "*.*"))))

(defn- compile-with-hash [{:keys [source-asset-dir target-dir]}]
  (status/line :head "compiling-js: with hash")
  (cmd "npx"
       "--yes"
       "esbuild"
       "--loader:.svg=copy"
       "--loader:.png=copy"
       "--loader:.ico=copy"
       "--entry-names=[name].[hash]"
       "--minify"
       "--sourcemap"
       (str "--outdir=" target-dir)
       (str (fs/file source-asset-dir "*.ico"))
       (str (fs/file source-asset-dir "*.png"))
       (str (fs/file source-asset-dir "*.css"))
       (str (fs/file source-asset-dir "*.svg"))))

(defn- transpile-to-js [{:keys [source-dir js-dir]}]
  (status/line :head "compile-js: compiling cljs with squint")
  (fs/delete-tree js-dir)
  (cmd "npx" "squint" "compile"
       "--extension" ".jsx"
       "--paths" source-dir
       "--output-dir" js-dir)
  ;; TODO: fix
  (fs/copy (fs/file source-dir "hljs-merge-plugin.js") (fs/file js-dir "cljdoc/client")))

(defn- compile-js [{:keys [js-dir js-entry-point js-out-name target-dir]}]
  (status/line :head "compile-js: bundle js")
  (cmd "npx"
       "--yes"
       "esbuild"
       "--target=es2017"
       "--resolve-extensions=.jsx,.js"
       "--minify"
       "--sourcemap"
       ;; elasticlunr did not expect to wrapped, expose it like so:
       "--define:lunr=window.lunr"
       "--entry-names=[name].[hash]"
       (str "--outdir=" target-dir)
       (str js-out-name "=" (fs/file js-dir js-entry-point))
       "--bundle"))

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
  (transpile-to-js opts)
  (compile-js opts)
  (generate-resource-map opts)
  (status/line :detail "Completed at %s"
               (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))))

(def ^:private changes-lock (Object.))

(defn- compile-all-no-exit [opts]
  (try
    (compile-all opts)
    (catch Throwable e
      (status/line :error "there was a problem: %s" (ex-message e)))))

(defn- change-detected [{:keys [path]} opts]
  (locking changes-lock
    (status/line :head "Recompiling\nChanged detected in %s" path)
    (compile-all-no-exit opts)
    (status/line :detail "Watching for changes...")))

(defn- setup-watch-compile [{:keys [source-dir source-asset-dir] :as opts}]
  (let [watch-dirs [source-asset-dir source-dir]]
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
                        :js-dir "target/js-transpiled"
                        :js-out-name "cljdoc"
                        :source-dir "front-end/src"
                        :js-entry-point "cljdoc/client/index.jsx"}]

      (if (get opts "--watch")
        (do
          (compile-all-no-exit compile-opts)
          (setup-watch-compile compile-opts))
        (compile-all compile-opts)))))

(main/when-invoked-as-script
 (apply -main *command-line-args*))
