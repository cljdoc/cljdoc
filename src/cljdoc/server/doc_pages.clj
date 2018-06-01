(ns cljdoc.server.doc-pages
  (:require [yada.yada :as yada]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cljdoc.renderers.html :as html]
            [cljdoc.render.build-req :as render-build-req]
            [cljdoc.cache]
            [cljdoc.routes :as routes]
            [cljdoc.grimoire-helpers :as grimoire-helpers]))

(defn doc-slug-parser
  "Because articles may reside in a nested hierarchy we need to manually parse
  some of the request URI"
  [{:keys [remainder] :as ctx}]
  (if-not (empty? remainder)
    (assoc-in ctx [:route-params :doc-slug-path] (cons (-> ctx :route-params :doc-page)
                                                       (clojure.string/split (subs remainder 1) #"/")))
    (assoc-in ctx [:route-params :doc-slug-path] [(-> ctx :route-params :doc-page)])))

(defn route-thing
  "Convert a route-id and it's params to the respective Grimoire Thing"
  [route-id params]
  (case route-id
    :group/index (grimoire-helpers/thing (-> params :group-id))
    :artifact/index (grimoire-helpers/thing (-> params :group-id)
                                            (-> params :artifact-id))

    (:artifact/version :artifact/doc :artifact/namespace)
    (grimoire-helpers/thing (-> params :group-id)
                            (-> params :artifact-id)
                            (-> params :version))))

(defn grimoire-loader
  "An interceptor to load relevant data for the request from our Grimoire store"
  [grimoire-dir ctx]
  (let [store (grimoire-helpers/grimoire-store grimoire-dir)
        group-thing (grimoire-helpers/thing (-> ctx :route-params :group-id))]
    (case (:id ctx)
      (:group/index :artifact/index)
      (do (log/info "Loading group cache bundle for" (:route-params ctx))
          (assoc ctx :cache-bundle (cljdoc.cache/bundle-group store group-thing)))

      (:artifact/version :artifact/doc :artifact/namespace)
      (let [version-thing (grimoire-helpers/version-thing
                           (-> ctx :route-params :group-id)
                           (-> ctx :route-params :artifact-id)
                           (-> ctx :route-params :version))]
        (log/info "Loading artifact cache bundle for" (:route-params ctx))
        (if (grimoire-helpers/exists? store version-thing)
          (assoc ctx :cache-bundle (cljdoc.cache/bundle-docs store version-thing))
          ctx)))))

(defrecord DocPage [grimoire-dir page-type]
  yada.resource/ResourceCoercion
  (as-resource [_]
    (yada/resource
     {:id page-type
      :description "The description to this example resource"
      :summary "An example resource"
      :produces "text/html"
      :path-info? (if (= :artifact/doc page-type) true false)
      :interceptor-chain (cond->> yada/default-interceptor-chain
                           true                        (cons (partial grimoire-loader grimoire-dir))
                           (= :artifact/doc page-type) (cons doc-slug-parser))
      :methods {:get (fn page-response [{:keys [route-params cache-bundle] :as ctx}]
                       (if-let [first-article-slug (and (= page-type :artifact/version)
                                                        (-> cache-bundle :cache-contents :version :doc first :attrs :slug))]
                         ;; instead of rendering a mostly white page we
                         ;; redirect to the README/first listed article for now
                         (assoc (:response ctx)
                                :status 302
                                :headers {"Location" (routes/path-for :artifact/doc (assoc route-params :doc-page first-article-slug))})
                         (if cache-bundle
                           (str (html/render page-type route-params cache-bundle))
                           (str (render-build-req/request-build-page route-params)))))}}))

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


(defn doc-page [grimoire-dir page-type]
  (->DocPage grimoire-dir page-type))

(comment
  (integrant.repl/reset)

  (clojure.string/split "abc/sd/" #"/")

  )
