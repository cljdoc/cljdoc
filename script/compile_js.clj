#!/usr/bin/env bb

(ns compile-js
  (:require [babashka.esbuild :as esbuild]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [helper.main :as main]
            [helper.shell :as shell]
            [lread.status-line :as status]
            [pod.babashka.fswatcher :as fw]
            [squint.compiler :as squint])
  (:import [java.security MessageDigest]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def args-usage "Valid args: [--watch|--test|--help]

Options
 --watch        Rebuild client-side assets if they change
 --test         Run tests via node
 --help         Show this help")

(defn- short-sha-bytes [b]
  (let [digest (MessageDigest/getInstance "SHA-256")
        sha (.digest digest b)]
    (apply str (map #(format "%02x" %) (take 4 sha)))))

(defn- short-sha-string [s]
  (short-sha-bytes (into-array Byte/TYPE (.getBytes s "UTF-8"))))

(defn- compile-copy
  "We could use a simple copy but maybe better to use esbuild for consistent console output"
  [{:keys [source-asset-dir source-asset-static-subdir target-dir]}]
  (status/line :head "compile-js: straight copy")
  (doseq [in-file (-> (fs/glob (fs/file source-asset-dir source-asset-static-subdir) "*.*") sort)
          :let [out-file (fs/file target-dir (fs/file-name in-file))]]
    (status/line :detail "copying %s\n to %s" in-file out-file)
    (fs/copy in-file out-file {:replace-existing true})))

(defn- compile-copy-with-hash
  [{:keys [source-asset-dir target-dir]}]
  (status/line :head "compile-js: copy with cache-busting")
  (doseq [in-file (-> (fs/glob source-asset-dir "*.{ico,png,svg}") sort)]
    (status/line :detail "cache-bust copying %s" in-file)
    (let [in-bytes (fs/read-all-bytes (fs/file in-file))
          hash (short-sha-bytes in-bytes)
          fname-ext (fs/file-name in-file)
          [fname ext] (fs/split-ext fname-ext)
          out-file (fs/file target-dir (str fname "." hash "." ext))]
      (status/line :detail " to %s" out-file)
      (fs/write-bytes out-file in-bytes))))

(defn- compile-transform-assets [{:keys [source-asset-dir target-dir]}]
  (status/line :head "compile-js: transform assets")
  (doseq [in-file (-> (fs/glob source-asset-dir "*.css") sort)]
    (status/line :detail "cache-bust transforming: %s" in-file)
    (let [fname-ext (fs/file-name in-file)
          [fname ext] (fs/split-ext fname-ext)
          in-content (slurp (fs/file in-file))
          {:keys [code map]} (esbuild/transform
                              in-content
                              {:loader :css
                               :minify true
                               :sourcefile fname-ext
                               :sourcemap :external})
          hash (short-sha-string code)
          code-file (fs/file target-dir (str fname "." hash "." ext))
          map-file (str code-file ".map")]
      (status/line :detail " to %s" code-file)
      (spit code-file
            (str code "\n/*# sourceMappingURL=" (fs/file-name map-file) " */\n"))
      (status/line :detail " to %s" map-file)
      (spit map-file map))))

(defn file->ns
  "app/util.cljs under src-dir becomes app.util."
  [src-dir file]
  (-> (str (fs/relativize src-dir file))
      (str/replace #"\.cljs$" "")
      (str/replace fs/file-separator ".")
      (str/replace "_" "-")))

(defn- compile-cljs-to-js [from-dir to-dir]
  (doseq [in-file (-> (fs/glob from-dir  "**.cljs") sort)
          :let [out-file (fs/path to-dir (str (file->ns from-dir in-file) ".jsx"))]]
    (status/line :detail "compiling %s\n to %s" in-file out-file)
    (spit (fs/file out-file)
          (squint/compile-string (slurp (fs/file in-file))
                                 {:resolve-ns (fn [ns] (str "./" ns ".jsx"))}))))

(defn- recreate-dir [dir]
  (fs/delete-tree dir)
  (fs/create-dirs dir))

(defn- compile-cljs [{:keys [source-dir test-dir js-dir]}]
  (status/line :head "compile-js: compiling cljs source code with squint")
  (compile-cljs-to-js source-dir js-dir)
  (when test-dir
    (status/line :head "compile-js: compiling cljs test code with squint")
    (compile-cljs-to-js test-dir js-dir)))

(defn- compile-copy-js [{:keys [source-dir js-dir]}]
  (status/line :head "compile-js: straight copy")
  (doseq [in-file (-> (fs/glob source-dir "*.js") sort)
          :let [out-file (fs/file js-dir (fs/file-name in-file))]]
    (status/line :detail "copying %s\n to %s" in-file out-file)
    (fs/copy in-file out-file {:replace-existing true})))

(defn- compile-js [{:keys [js-dir] :as opts}]
  (recreate-dir js-dir)
  (compile-cljs opts)
  (compile-copy-js opts))

(def squint-js
  "root dir of the squint checkout, core.js is under <root>/src/squint "
  (str (-> (fs/path (io/resource "squint/core.js"))
           fs/parent
           fs/parent
           fs/parent)))

(defn- compile-bundle [{:keys [js-dir js-entry-point js-out-name js-out-ext target-dir platform]}]
  (status/line :head "compile-js: bundle js")
  (println "squint-js" squint-js)
  (let [bundle (esbuild/build {:entry-points [(str (fs/file js-dir js-entry-point))]
                               :bundle true
                               :jsx :automatic
                               :alias {"react" "preact/compat"
                                       "react-dom" "preact/compat"
                                       "react/jsx-runtime" "preact/jsx-runtime"
                                       "squint-cljs" squint-js}
                               :target :es2017
                               :minify true
                               :platform platform
                               :metafile true
                               :sourcemap :linked
                               ;; :outdir required when specifying :sourcemap
                               :outdir target-dir})
        {:keys [sourcemap code]} (reduce (fn [acc {:keys [path contents]}]
                                           (if (str/ends-with? path ".map")
                                             (assoc acc :sourcemap contents)
                                             (assoc acc :code contents)))
                                         {}
                                         (:outputs bundle))
        hash (short-sha-string code)
        code-file (fs/file target-dir (str js-out-name "." hash "." js-out-ext))
        map-file (str code-file ".map")
        ;; esbuild does not expect us to do our own hashing, fixup referenced map file
        code (str/replace-first code
                                "//# sourceMappingURL=cljdoc.client.index.js.map"
                                (str "//# sourceMappingURL=" (fs/file-name map-file)))
        report (-> bundle :metafile (esbuild/analyze-metafile {:verbose true}) :report)]
    (status/line :detail " to %s" code-file)
    (spit code-file code)
    (status/line :detail " to %s" map-file)
    (spit map-file sourcemap)
    (status/line :detail "build report:\n%s" report)))

(defn- resource-map
  "Map of non-hashed to hashed resource."
  [{:keys [target-dir]}]
  (reduce (fn [acc n]
            (let [f (fs/file-name n)
                  non-hashed-f (str/replace-first f #"\.[a-f0-9]{8}\." ".")]
              (assoc acc (str "/" non-hashed-f) (str "/" f))))
          (sorted-map)
          (sort (fs/list-dir target-dir))))

(defn- generate-resource-map [{:keys [manifest-out-dir] :as opts}]
  (status/line :head "compile-js: generate manifest")
  (fs/create-dirs manifest-out-dir)
  (let [f (fs/file manifest-out-dir "manifest.edn")]
    (with-open [out (io/writer f)]
      (pprint/write (resource-map opts) :stream out))
    (status/line :detail "Wrote: %s" f)))

(defn- compile-all [{:keys [target-dir] :as opts}]
  (recreate-dir target-dir)
  (compile-copy opts)
  (compile-copy-with-hash opts)
  (compile-transform-assets opts)
  (compile-js opts)
  (compile-bundle opts)
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

(defn- setup-watch-compile [{:keys [source-dir source-asset-dir test-dir] :as opts}]
  (let [watch-dirs (into [] (remove nil? [source-asset-dir source-dir test-dir]))]
    (status/line :detail "Watching for changes in... %s" watch-dirs)
    (doseq [d watch-dirs]
      (fw/watch d
                (fn [event]
                  (change-detected event opts))
                {:recursive true}))
    (deref (promise))))

(defn -main [& args]
  (when-let [opts (main/doc-arg-opt args-usage args)]
    (let [compile-opts (cond-> {:target-dir "resources-compiled/public/out"
                                :manifest-out-dir "resources-compiled" ;; no need for this to be public
                                :source-asset-dir "resources/public"
                                :source-asset-static-subdir "static"
                                :js-dir "target/js-compiled"
                                :js-out-name "cljdoc"
                                :source-dir "front-end/src"
                                :platform :browser
                                :js-out-ext "js"
                                :js-entry-point "cljdoc.client.index.jsx"}
                         (get opts "--test")
                         (assoc
                          :target-dir "target/js-test-out"
                          :js-dir "target/js-test-compiled"
                          :test-dir "front-end/test"
                          :platform :node
                          :js-out-ext "cjs" ;; so that node can run resulting bundle
                          :js-entry-point "cljdoc.client.test-runner.jsx"))]
      (fs/create-dirs (:target-dir compile-opts))
      (if (get opts "--watch")
        (do
          (compile-all-no-exit compile-opts)
          (setup-watch-compile compile-opts))
        (do (compile-all compile-opts)
            (when (get opts "--test")
              (status/line :head "compile-js: Running tests")
              (let [bundled-js (-> (fs/glob (:target-dir compile-opts) "cljdoc.*.cjs")
                                   first
                                   str)]
                (shell/command "node" bundled-js))))))))

(main/when-invoked-as-script
 (apply -main *command-line-args*))
