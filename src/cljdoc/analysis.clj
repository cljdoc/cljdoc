(ns cljdoc.analysis
  (:require [codox.main]
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
   :exclude-vars #"^(map)?->\p{Upper}"})

(defn sanitize-cdx [cdx-namespace]
  ;; :publics contain a field path which is a
  ;; file, this cannot be serialized accross
  ;; pod boundaries by default
  (let [clean-public    #(update % :path (fn [file] (.getPath file)))
        remove-unneeded #(dissoc % :path :members)  ; TODO what are members?
        stringify-name  #(update % :name name)]
    (-> cdx-namespace
        (update :name name)
        (update :publics #(map (comp stringify-name remove-unneeded) %)))))

(defn codox-namespaces [namespaces jar-contents-path platf]
  (let [config (codox-config namespaces jar-contents-path platf)]
    ;; TODO print versions for Clojure/CLJS and other important deps
    (boot.util/info "Analysing sources for platform %s\n" (pr-str platf))
    (boot.util/dbug "ClojureScript version %s\n" (cljs.util/clojurescript-version))
    (boot.util/dbug "Codox opts: %s\n" config)
    (->> (#'codox.main/read-namespaces config)
         (mapv sanitize-cdx)
         ;; Ensure everything is fully realized, see
         ;; https://github.com/boot-clj/boot/issues/683
         (clojure.walk/prewalk identity))))


