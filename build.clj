(ns build
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [build-shared :as bs]
   [clj-kondo.core :as kondo]
   [clojure.edn :as edn]
   [clojure.tools.build.api :as b]))

(def basis (b/create-basis {:project "deps.edn"}))

(defn compile-java [_opts]
  (println "Compiling java sources")
  (b/javac {:src-dirs bs/sources
            :class-dir bs/class-dir
            :basis basis
            :javac-opts ["--release" "17"
                         "-Xlint:all,-serial,-processing"
                         "-Werror"]} ))

(defn lint-cache
  "Build lint cache as a build task to avoid blowing max command line length on Windows"
  [_opts]
  (fs/delete-tree ".clj-kondo/.cache")
  (let [bb-deps (-> (process/shell {:out :string} "bb print-deps")
                    :out
                    edn/read-string)
        ;; ideally we'd be smarter about merging bb-deps, i.e, choosing latest version
        ;; for any libs that overlap, but this should be good enough for now
        basis (b/create-basis {:aliases [:test :cli] :extra bb-deps})
        cp-roots (:classpath-roots basis)]
    (println "- copying lib configs and creating cache")
    (kondo/run! {:skip-lint true :copy-configs true :dependencies true :parallel true :lint cp-roots})))


(defn download-deps
  "Download all deps for all aliases"
  [_]
  (doseq [deps-edn ["deps.edn" "modules/nvd-scan/deps.edn" "ops/exoscale/deploy/deps.edn"]]
    (let [aliases (->> deps-edn
                       slurp
                       edn/read-string
                       :aliases
                       keys)
          deps-dir (str (fs/parent deps-edn))]
      (println "-" deps-edn)
       ;; one at a time because aliases with :replace-deps will... well... you know.
      (println "Bring down default deps")
      (b/create-basis {:dir deps-dir})
      (doseq [a (sort aliases)]
        (println "Bring down deps for alias" a)
        (b/create-basis {:dir deps-dir :aliases [a]})))))
