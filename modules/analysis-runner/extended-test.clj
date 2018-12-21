(require '[clojure.java.shell :as sh]
         '[clojure.string :as string]
         '[clojure.pprint])

(def candidates
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

(->> (for [[project version url-base] candidates]
       (let [args ["clojure" "-m" "cljdoc.analysis.runner" project version (str url-base ".jar") (str url-base ".pom")]
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

