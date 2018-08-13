(ns cljdoc.analysis.impl
  (:require [codox.main :as codox]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [cljs.util :as cljs-util]))

(def ok-to-fail?
  #{"clojure.parallel"})

(defn codox-config [namespaces jar-contents-path platf]
  (assert (string? jar-contents-path) (str "was: " (pr-str jar-contents-path)))
  (assert (contains? #{"clj" "cljs"} platf) (str "was: " (pr-str platf)))
  {:language     (get {"clj" :clojure, "cljs" :clojurescript} platf)
   ;; not sure what :root-path is needed for
   :root-path    (System/getProperty "user.dir")
   :source-paths [jar-contents-path]
   :namespaces   (or namespaces :all)
   :exception-handler (fn ex-handler [ex f-or-ns]
                        (when-not (ok-to-fail? (str f-or-ns))
                          (throw (ex-info (format "Could not analyze %s" f-or-ns) {} ex))))
   :metadata     {}
   :writer       'clojure.core/identity
   :exclude-vars #"^(map)?->\p{Upper}"})

(defn sanitize-cdx
  [cdx-namespace]
  ;; :publics contain a field :path which is a java.io.File,
  ;; files cannot be serialized across pod boundaries by default
  (assert (:name cdx-namespace))
  (let [remove-path #(dissoc % :path)
        remove-arglists #(if (and (find % :arglists)
                                  (nil? (:arglists %)))
                           ;; Instaparse 1.4.9 and other projects exhibited
                           ;; this issue. Might be an inconsistency in Codox?
                           (do (printf "WARNING Removing nil :arglists from %s" %)
                               (dissoc % :arglists))
                           %)]
    (-> cdx-namespace
        (update :publics #(map remove-path %))
        (update :publics #(map remove-arglists %)))))

(defn codox-namespaces [namespaces jar-contents-path platf]
  (let [config (codox-config namespaces jar-contents-path platf)]
    ;; TODO print versions for Clojure/CLJS and other important deps
    (printf "Analysing sources for platform %s\n" (pr-str platf))
    (printf "Clojure version %s\n" (clojure-version))
    (printf "ClojureScript version %s\n" (cljs-util/clojurescript-version))
    (printf "Codox opts: %s\n" config)
    (->> (codox/generate-docs config)
         :namespaces
         (mapv sanitize-cdx)
         ;; Ensure everything is fully realized, see
         ;; https://github.com/boot-clj/boot/issues/683
         (clojure.walk/prewalk identity))))

(defn -main [namespaces jar-contents-path platf file]
  (printf "Args:\n  - namespaces: %s\n  - jar-contents-path: %s\n  - platf: %s\n  - file: %s\n"
          (pr-str namespaces) (pr-str jar-contents-path) (pr-str platf) (pr-str file))
  (let [ana-result (codox-namespaces (edn/read-string namespaces) jar-contents-path platf)]
    (printf "Writing %s\n" file)
    (spit file (pr-str ana-result))))


