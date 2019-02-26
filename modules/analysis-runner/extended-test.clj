(require '[clojure.java.shell :as sh]
         '[clojure.string :as string]
         '[clojure.java.io :as io]
         '[clojure.pprint])

(import '[java.nio.file Files]
        '[java.net URI])

(def local-candidates
  [;; known to work
   ["metosin/muuntaja" "unpublished" "http://repo.clojars.org/metosin/muuntaja/0.6.3/muuntaja-0.6.3"]])

(def remote-candidates
  [;; depends on ring/ring-core which requires [javax.servlet/servlet-api "2.5"]
   ["metosin/compojure-api" "2.0.0-alpha27" "http://repo.clojars.org/metosin/compojure-api/2.0.0-alpha27/compojure-api-2.0.0-alpha27"]
   ;; depends on tools.namepspace 0.3.0-alpha4, cljdoc explicitly declared 0.2.11
   ["iced-nrepl" "0.2.5" "https://repo.clojars.org/iced-nrepl/iced-nrepl/0.2.5/iced-nrepl-0.2.5"]
   ;; known to work
   ["bidi" "2.1.3" "http://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3"]
   ;; had some issues with older ClojureScript and analysis env
   ["orchestra" "2018.11.07-1" "http://repo.clojars.org/orchestra/orchestra/2018.11.07-1/orchestra-2018.11.07-1"]
   ;; might have had some issues related to old versions of core.async in the past
   ["manifold" "0.1.8" "http://repo.clojars.org/manifold/manifold/0.1.8/manifold-0.1.8"]
   ;; TEST DEFUNCT because snapshots changed https://dev.clojure.org/jira/browse/CLJS-2964
   ;; ["speculative" "0.0.3-SNAPSHOT" "http://repo.clojars.org/speculative/speculative/0.0.3-SNAPSHOT/speculative-0.0.3-20181116.104047-47"]
   ;; https://github.com/cljdoc/cljdoc/issues/247
   ["io.aviso/pretty" "0.1.29" "http://repo.clojars.org/io/aviso/pretty/0.1.29/pretty-0.1.29"]])

(def sep
  "\n---------------------------------------------------------------------------")

(defn exit [results]
  (clojure.pprint/print-table results)
  (shutdown-agents)
  (if (some false? (map :success? results))
    (System/exit 1)
    (System/exit 0)))

(def clean-temp
  "Disable for debugging"
  true)

(def temp-dir
  (let [path (Files/createTempDirectory
              "cljdoc-test"
              (into-array java.nio.file.attribute.FileAttribute []))
        file (.toFile path)]
    (when clean-temp
      ;; This will remove temp-dir only if empty
      (.deleteOnExit file))
    file))

(defn mktemp [name]
  (let [file (io/file temp-dir name)]
    (when clean-temp
      (.deleteOnExit file))
    file))

(defn download-temp! [url name]
  (let [file (mktemp name)]
    (with-open [in  (io/input-stream (io/as-url url))
                out (io/output-stream file)]
      (io/copy in out))
    (.getAbsolutePath file)))

(defn remote->args [[project version base-url]]
  {:project project
   :version version
   :jarpath (str base-url ".jar")
   :pompath (str base-url ".pom")})

(defn local->args [[project version base-url]]
  (let [{:keys [jarpath pompath] :as args}
        (remote->args [project version base-url])
        prefix (str (string/replace project #"/" "-") "-" version)]
    (assoc args
           :jarpath (download-temp! jarpath (str prefix ".jar"))
           :pompath (download-temp! pompath (str prefix ".pom")))))

(->> (for [{:keys [project version] :as args}
           (concat (map local->args local-candidates)
                   (map remote->args remote-candidates))]
       (let [args ["clojure" "-m" "cljdoc.analysis.runner-ng" (pr-str args)]
             _ (do (println "Analyzing" project version) (println (string/join " " args) sep))
             {:keys [exit out]} (apply sh/sh args)]
         (when-not (zero? exit)
           (println "Analysis failed for" project version sep)
           (println out)
           (println sep))

         {:project project
          :version version
          :success? (zero? exit)}))
     (sort-by :success?)
     (exit))

