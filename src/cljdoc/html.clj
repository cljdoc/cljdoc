(ns cljdoc.html
  (:require [cljdoc.routes :as r]
            [hiccup2.core :as hiccup]
            [hiccup.page]
            [clojure.java.io :as io]
            [grimoire.api.fs]
            [grimoire.api.fs.read]
            [grimoire.api :as grim]
            [grimoire.things :as things]
            [grimoire.either :as e]))

(defn page [contents]
  (hiccup/html {:mode :html}
               (hiccup.page/doctype :html5)
               [:html {} contents]))

(defn index-page [namespaces]
  [:div
   [:ul
    (for [ns namespaces]
      [:li
       [:a {:href (str "/" (things/thing->path ns) "/")} (:name ns)]])]])

(defn namespace-page [namespace defs]
  [:div
   [:h1 (:name namespace)]
   [:ul
    (for [adef (map :name defs)]
      [:li adef])]])

(defn write-docs [store platform ^java.io.File out-dir]
  (let [namespaces (e/result (grim/list-namespaces store platform))
        out-file   (io/file out-dir (things/thing->path platform) "index.html")]
    (io/make-parents out-file)
    (->> (-> (index-page namespaces) page str)
         (spit out-file))
    (doseq [ns namespaces
            :let [defs (e/result (grim/list-defs store ns))
                  outf (io/file out-dir (things/thing->path ns) "index.html")]]
      (io/make-parents outf)
      (spit outf (-> (namespace-page ns defs) page str)))))

(comment

  ;; (require 'grimoire.api.fs)
  (def out (doto (io/file "target" "grim-html-repl") (.mkdir)))
  (def store (grimoire.api.fs/->Config "target/grimoire" "" ""))
  (def platf (-> (things/->Group "boot")
                 (things/->Artifact "core")
                 (things/->Version "2.7.2")
                 (things/->Platform "clj")))

  (things/thing->path platf)

  (write-docs store platf out)
  
  (defn namespace-hierarchy [ns-list]
    (reduce (fn [hierarchy ns-string]
              (let [ns-path (clojure.string/split ns-string #"\.")]
                (if (get-in hierarchy ns-path)
                  hierarchy
                  (assoc-in hierarchy ns-path nil))))
            {}
            ns-list))

  (namespace-hierarchy (map :name namespaces))


  )
