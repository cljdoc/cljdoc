(ns cljdoc.server.doc-pages
  (:require [yada.yada :as yada]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cljdoc.renderers.html :as html]
            [cljdoc.cache]
            [cljdoc.grimoire-helpers :as grimoire-helpers]))

(defn doc-slug-parser
  "Because articles may reside in a nested hierarchy we need to manually parse
  some of the request URI"
  [ctx]
  (if-not (empty? (:remainder ctx))
    (assoc-in ctx [:route-params :doc-slug-path] (cons (-> ctx :route-params :doc-page)
                                                       (clojure.string/split (:remainder ctx) #"/")))
    (assoc-in ctx [:route-params :doc-slug-path] [(-> ctx :route-params :doc-page)])))

(defn grimoire-loader
  "An interceptor to load relevant data for the request from our Grimoire store"
  [ctx]
  (log/info "Loading cache bundle for" (:route-params ctx))
  (assoc ctx
         :cache-bundle
         (cljdoc.cache/bundle-docs
          (cljdoc.grimoire-helpers/grimoire-store (io/file "data/grimoire/"))
          (cljdoc.grimoire-helpers/version-thing
           (-> ctx :route-params :group-id)
           (-> ctx :route-params :artifact-id)
           (-> ctx :route-params :version)))))

(defrecord DocPage [page-type]
  yada.resource/ResourceCoercion
  (as-resource [_]
    (yada/resource
     {:id page-type
      :description "The description to this example resource"
      :summary "An example resource"
      :produces "text/html"
      :path-info? (if (= :artifact/doc page-type) true false)
      :interceptor-chain (cond->> yada/default-interceptor-chain
                           (not (#{:group/index :artifact/index} page-type)) (cons grimoire-loader)
                           (= :artifact/doc page-type)                       (cons doc-slug-parser))
      :methods {:get (fn page-response [{:keys [route-params cache-bundle] :as ctx}]
                       (str (or (html/render page-type route-params cache-bundle)
                                "NOT IMPLEMENTED"))
                       #_(str "hello world " page-type " " route-params))}}))

  bidi.bidi/Matched
  (resolve-handler [this m]
    (let [r (yada.resource/as-resource this)]
      (if (:path-info? r)
        (assoc m :handler r)
        (bidi.bidi/succeed r m))))

  (unresolve-handler [this m]
    (when
        (or (= this (:handler m))
            (and page-type (= page-type (:handler m))))
      "")))


(defn doc-page [page-type]
  (->DocPage page-type)) 

#_(defn doc-page [page-type]
  (yada/resource
    {:id page-type
     :description "The description to this example resource"
     :summary "An example resource"
     :produces "text/html"
     :methods {:get (fn [_] "hello world")}}))


(comment
  (integrant.repl/reset)

  (clojure.string/split "abc/sd/" #"/")

  )
