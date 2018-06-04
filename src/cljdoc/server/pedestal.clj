(ns cljdoc.server.pedestal
  (:require [cljdoc.grimoire-helpers :as grimoire-helpers]
            [cljdoc.render.build-req :as render-build-req]
            [cljdoc.render.build-log :as render-build-log]
            [cljdoc.render.home :as render-home]
            [cljdoc.renderers.html :as html]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.api :as api]
            [cljdoc.server.pedestal]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [byte-streams :as bs]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body]
            [io.pedestal.http.route :as route]))

(defn ok! [ctx body]
  (assoc ctx :response {:status 200 :body body}))

(defn ok-html! [ctx body]
  (assoc ctx :response {:status 200
                        :body (str body)
                        :headers {"Content-Type" "text/html"}}))

(declare url-for)

(def render-interceptor
  {:name  ::render
   :error (fn render-error [ctx ex-info]
            (log/error ex-info
                       "Exception when processing render-req"
                       {:path-params (-> ctx :request :path-params)
                        :route-name  (-> ctx :route :route-name)}))
   :enter (fn render-doc [{:keys [cache-bundle] :as ctx}]
            (let [path-params (-> ctx :request :path-params)
                  page-type   (-> ctx :route :route-name)]
              (if-let [first-article-slug (and (= page-type :artifact/version)
                                               (-> cache-bundle :cache-contents :version :doc first :attrs :slug))]
                ;; instead of rendering a mostly white page we
                ;; redirect to the README/first listed article for now
                (assoc ctx
                       :response
                       {:status 302
                        :headers {"Location"  (@(:url-for ctx) :artifact/doc :params (assoc path-params :article-slug first-article-slug))}})

                (if cache-bundle
                  (ok-html! ctx (html/render page-type path-params cache-bundle))
                  (ok-html! ctx (render-build-req/request-build-page path-params))))))})

(def doc-slug-parser
  "Because articles may reside in a nested hierarchy we need to manually parse
  some of the request URI"
  {:name ::doc-slug-parser
   :enter (fn [ctx]
            (->> (clojure.string/split (get-in ctx [:request :path-params :article-slug])  #"/")
                 (assoc-in ctx [:request :path-params :doc-slug-path])))})

(defn grimoire-loader
  "An interceptor to load relevant data for the request from our Grimoire store"
  [store route-name]
  {:name  ::grimoire-loader
   :enter (fn [{:keys [request] :as ctx}]
            (let [group-thing (grimoire-helpers/thing (-> request :path-params :group-id))]
              (case route-name
                (:group/index :artifact/index)
                (do (log/info "Loading group cache bundle for" (:path-params request))
                    (assoc ctx :cache-bundle (cljdoc.cache/bundle-group store group-thing)))

                (:artifact/version :artifact/doc :artifact/namespace)
                (let [version-thing (grimoire-helpers/version-thing
                                     (-> request :path-params :group-id)
                                     (-> request :path-params :artifact-id)
                                     (-> request :path-params :version))]
                  (log/info "Loading artifact cache bundle for" (:path-params request))
                  (if (grimoire-helpers/exists? store version-thing)
                    (assoc ctx :cache-bundle (cljdoc.cache/bundle-docs store version-thing))
                    ctx)))))})

(def article-locator
  {:name ::article-locator
   :enter (fn article-locator [ctx]
            ;; TOOD read correct article from cache-bundle, put
            ;; somewhere in ctx if not found 404
            )})

(defn view [grimoire-store route-name]
  (->> [(when (= :artifact/doc route-name) doc-slug-parser)
        (grimoire-loader grimoire-store route-name)
        render-interceptor]
       (keep identity)
       (vec)))

(defn documentation-routes [{:keys [grimoire-store]}]
  (->> {:group/index        "/d/:group-id"
        :artifact/index     "/d/:group-id/:artifact-id"
        :artifact/version   "/d/:group-id/:artifact-id/:version"
        :artifact/doc       "/d/:group-id/:artifact-id/:version/doc/*article-slug"
        :artifact/namespace "/d/:group-id/:artifact-id/:version/api/:namespace"}
       (reduce-kv (fn [routes route-name route-pattern]
                    (conj routes [route-pattern :get (view grimoire-store route-name) :route-name route-name]))
                  #{})))

(defn request-build
  [{:keys [analysis-service build-tracker]}]
  {:name ::request-build
   :enter (fn [ctx]
            (let [build-id (api/initiate-build
                            {:analysis-service analysis-service
                             :build-tracker    build-tracker
                             :project          (get-in ctx [:request :form-params :project])
                             :version          (get-in ctx [:request :form-params :version])})]
              (assoc ctx
                     :response
                     {:status 303
                      :headers {"Location" (str "/builds/" build-id)}})))})

(defn full-build
  [{:keys [dir build-tracker]}]
  {:name ::full-build
   :enter (fn [ctx]
            (api/full-build
             {:dir           dir
              :build-tracker build-tracker}
             (get-in ctx [:request :form-params]))
            (ok! ctx nil))})

(defn circle-ci-webhook
  [{:keys [analysis-service build-tracker]}]
  {:name ::circle-ci-webhook
   :enter (fn [ctx]
            (let [build-num (get-in ctx [:request :json-params "payload" "build_num"])
                  project   (get-in ctx [:request :json-params "payload" "build_parameters" "CLJDOC_PROJECT"])
                  version   (get-in ctx [:request :json-params "payload" "build_parameters" "CLJDOC_PROJECT_VERSION"])
                  build-id  (get-in ctx [:request :json-params "payload" "build_parameters" "CLJDOC_BUILD_ID"])
                  status    (get-in ctx [:request :json-params "payload" "status"])
                  cljdoc-edn (cljdoc.util/cljdoc-edn project version)
                  artifacts  (-> (analysis-service/get-circle-ci-build-artifacts analysis-service build-num)
                                 :body bs/to-string json/read-value)]
              (if-let [artifact (and (= status "success")
                                     (= 1 (count artifacts))
                                     (= cljdoc-edn (get (first artifacts) "path"))
                                     (first artifacts))]
                (let [full-build-req (api/run-full-build {:project project
                                                          :version version
                                                          :build-id build-id
                                                          :cljdoc-edn (get artifact "url")})]
                  (assoc-in ctx [:response :status] (:status full-build-req)))

                (do
                  (if (= status "success")
                    (build-log/failed! build-tracker build-id "unexpected-artifacts")
                    (build-log/failed! build-tracker build-id "analysis-job-failed"))
                  (assoc-in ctx [:response :status] 200)))))})

(def pong
  {:name ::pong :enter (fn [ctx] (assoc-in ctx [:response] {:status 200 :body "pong"}))})

(def request-build-validate
  ;; TODO quick and dirty for now
  {:name ::request-build-validate
   :enter (fn request-build-validate [ctx]
            (if (and (some-> ctx :request :form-params :project string?)
                     (some-> ctx :request :form-params :version string?))
              ctx
              (assoc ctx :response {:status 400 :headers {}})))})

(defn api-routes [{:keys [analysis-service build-tracker dir] :as opts}]
  #{["/api/ping" :get pong]
    ["/api/request-build2" :post [(body/body-params) request-build-validate (request-build opts)] :route-name :request-build]
    ["/api/full-build" :post [(body/body-params) (full-build opts)] :route-name :full-build]
    (when (analysis-service/circle-ci? analysis-service)
      ["/hooks/circle-ci" :post [(body/body-params) (full-build opts)] :route-name :circle-ci-webhook])})

(defn build-log-routes [build-tracker]
  #{["/builds/:id" :get {:name ::build-show
                         :enter (fn build-show-render [ctx]
                                  (if-let [build-info (->> ctx :request :path-params :id
                                                           (build-log/get-build build-tracker))]
                                    (ok-html! ctx (cljdoc.render.build-log/build-page build-info))
                                    ;; Not setting :response implies 404 response
                                    ctx))}]
    ["/builds" :get {:name ::build-index
                     :enter (fn build-index-render [ctx]
                              (->> (build-log/recent-builds build-tracker 100)
                                   (cljdoc.render.build-log/builds-page)
                                   (ok-html! ctx)))}]})

(defn info-pages []
  #{["/" :get {:name ::home :enter #(ok-html! % (render-home/home))}]})

(defn routes [{:keys [host port scheme] :as opts}]
  (route/expand-routes
   (->> [(when host
           ;; https://github.com/pedestal/pedestal/issues/570
           #{(select-keys opts [:host :port :scheme])})
         (documentation-routes opts)
         (api-routes opts)
         (build-log-routes (:build-tracker opts))
         (info-pages)]
        (reduce into #{}))))

;; (def url-for
;;   ;; TODO parameterize
;;   (route/url-for-routes (routes {:host "cljdox.xyz"
;;                                  :port 8888
;;                                  :scheme :https})))

(defmethod ig/init-key :cljdoc/pedestal [_ opts]
  (log/info "Starting pedestal on port" opts)
  ;; For some reason passing the Grimoire store as a key in the opts map
  ;; doesn't work, arrives as the following:
  ;; (:grimoire.api.fs/Config {:docs "data/grimoire", :examples "", :notes ""})
  (let [grimoire-store (cljdoc.grimoire-helpers/grimoire-store
                        (clojure.java.io/file (:dir opts) "grimoire"))]
    (-> {::http/routes (routes (assoc opts :grimoire-store grimoire-store))
         ::http/type   :jetty
         ::http/join?  false
         ::http/port   (:port opts)
         ::http/resource-path "public"}
        (http/create-server)
        (http/start))))

(defmethod ig/halt-key! :cljdoc/pedestal [_ server]
  (http/stop server))

(comment
  (def gs (grimoire-helpers/grimoire-store (clojure.java.io/file "data" "grimoire")))

  (def s (http/start (create-server (routes {}))))

  (http/stop s)

  (clojure.pprint/pprint
   (routes {:grimoire-store gs}))

  (require 'io.pedestal.test)

  (io.pedestal.test/response-for (:io.pedestal.http/service-fn s) :post "/api/request-build2")

  )
