(ns cljdoc.analysis.impl
  (:require [clojure.tools.logging :as log]
            [codox.main]
            [cljs.util]))

(defn codox-config [namespaces jar-contents-path platf]
  (assert (string? jar-contents-path) (str "was: " (pr-str jar-contents-path)))
  (assert (contains? #{"clj" "cljs"} platf) (str "was: " (pr-str platf)))
  {:language     (get {"clj" :clojure, "cljs" :clojurescript} platf)
   ;; not sure what :root-path is needed for
   :root-path    (System/getProperty "user.dir")
   :source-paths [jar-contents-path]
   :namespaces   (or namespaces :all)
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
                           (do (log/warnf "Removing nil :arglists from %s" %)
                               (dissoc % :arglists))
                           %)]
    (-> cdx-namespace
        (update :publics #(map remove-path %))
        (update :publics #(map remove-arglists %)))))

(defn codox-namespaces [namespaces jar-contents-path platf]
  (let [config (codox-config namespaces jar-contents-path platf)]
    ;; TODO print versions for Clojure/CLJS and other important deps
    (boot.util/info "Analysing sources for platform %s\n" (pr-str platf))
    (boot.util/dbug "ClojureScript version %s\n" (cljs.util/clojurescript-version))
    (boot.util/dbug "Codox opts: %s\n" config)
    (->> (codox.main/generate-docs config)
         :namespaces
         (mapv sanitize-cdx)
         ;; Ensure everything is fully realized, see
         ;; https://github.com/boot-clj/boot/issues/683
         (clojure.walk/prewalk identity))))


