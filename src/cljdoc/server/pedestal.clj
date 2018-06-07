(ns cljdoc.server.pedestal
  (:require [cljdoc.grimoire-helpers :as grimoire-helpers]
            [cljdoc.render.build-req :as render-build-req]
            [cljdoc.render.build-log :as render-build-log]
            [cljdoc.render.home :as render-home]
            [cljdoc.renderers.html :as html]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.routes :as routes]
            [cljdoc.server.api :as api]
            [cljdoc.util :as util]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.sentry :as sentry]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [integrant.core :as ig]
            [cheshire.core :as json]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body]))

(defn ok! [ctx body]
  (assoc ctx :response {:status 200 :body body}))

(defn ok-html! [ctx body]
  (assoc ctx :response {:status 200
                        :body (str body)
                        :headers {"Content-Type" "text/html"}}))

(def render-interceptor
  {:name  ::render
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
                        :headers {"Location"  (routes/url-for :artifact/doc :params (assoc path-params :article-slug first-article-slug))}})

                (if cache-bundle
                  (ok-html! ctx (html/render page-type path-params cache-bundle))
                  (ok-html! ctx (render-build-req/request-build-page path-params))))))})

(def doc-slug-parser
  "Because articles may reside in a nested hierarchy we need to manually parse
  some of the request URI"
  {:name ::doc-slug-parser
   :enter (fn [ctx]
            (->> (string/split (get-in ctx [:request :path-params :article-slug])  #"/")
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
            (let [build-num (get-in ctx [:request :json-params :payload :build_num])
                  project   (get-in ctx [:request :json-params :payload :build_parameters :CLJDOC_PROJECT])
                  version   (get-in ctx [:request :json-params :payload :build_parameters :CLJDOC_PROJECT_VERSION])
                  build-id  (get-in ctx [:request :json-params :payload :build_parameters :CLJDOC_BUILD_ID])
                  status    (get-in ctx [:request :json-params :payload :status])
                  success?  (contains? #{"success" "fixed"} status)
                  cljdoc-edn (cljdoc.util/cljdoc-edn project version)
                  artifacts  (-> (analysis-service/get-circle-ci-build-artifacts analysis-service build-num)
                                 :body json/parse-string)]
              (if-let [artifact (and success?
                                     (= 1 (count artifacts))
                                     (= cljdoc-edn (get (first artifacts) "path"))
                                     (first artifacts))]
                (do
                  (api/run-full-build {:project project
                                       :version version
                                       :build-id build-id
                                       :cljdoc-edn (get artifact "url")})
                  (assoc-in ctx [:response] {:status 200 :headers {}}))

                (do
                  (if success?
                    (build-log/failed! build-tracker build-id "unexpected-artifacts")
                    (do (log/error :analysis-job-failed status)
                        (build-log/failed! build-tracker build-id "analysis-job-failed")))
                  (assoc-in ctx [:response :status] 200)))))})

(def request-build-validate
  ;; TODO quick and dirty for now
  {:name ::request-build-validate
   :enter (fn request-build-validate [ctx]
            (if (and (some-> ctx :request :form-params :project string?)
                     (some-> ctx :request :form-params :version string?))
              ctx
              (assoc ctx :response {:status 400 :headers {}})))})

(defn show-build
  [build-tracker]
  {:name ::build-show
   :enter (fn build-show-render [ctx]
            (if-let [build-info (->> ctx :request :path-params :id
                                     (build-log/get-build build-tracker))]
              (ok-html! ctx (cljdoc.render.build-log/build-page build-info))
              ;; Not setting :response implies 404 response
              ctx))})

(defn all-builds
  [build-tracker]
  {:name ::build-index
   :enter (fn build-index-render [ctx]
            (->> (build-log/recent-builds build-tracker 100)
                 (cljdoc.render.build-log/builds-page)
                 (ok-html! ctx)))})

(defn badge-interceptor []
  {:name ::badge
   :enter (fn badge [ctx]
            (log/info "Badge req headers" (-> ctx :request :headers))
            (let [project (-> ctx :request :path-params :project)
                  release (try (repos/latest-release-version project)
                               (catch Exception e
                                 (log/warnf "Could not find release for %s" project)))
                  url     (if release
                            (format "https://img.shields.io/badge/cljdoc-%s-blue.svg"
                                    (string/replace release #"-" "--"))
                            (format "https://img.shields.io/badge/cljdoc-%s-red.svg" "no release found"))
                  badge   (clj-http.lite.client/get url {:headers {"User-Agent" "clj-http-lite"}})]
              (->> {:status 200
                    :headers {"Content-Type" "image/svg+xml;charset=utf-8"
                              "Cache-Control" "no-cache"}
                    :body (:body badge)}
                   (assoc ctx :response))))})

(defn jump-interceptor []
  {:name ::jump
   :enter (fn jump [ctx]
            (let [project (-> ctx :request :path-params :project)
                  release (try (repos/latest-release-version project)
                               (catch Exception e
                                 (log/warnf "Could not find release for %s" project)))]
              (->> (if release
                     {:status 302
                      :headers {"Location" (routes/url-for :artifact/version
                                                           :params
                                                           {:group-id (util/group-id project)
                                                            :artifact-id (util/artifact-id project)
                                                            :version release})}}
                     {:status 404
                      :headers {}
                      :body (format "Could not find release for %s" project)})
                   (assoc ctx :response))))})

(defn route-resolver
  [{:keys [build-tracker grimoire-store] :as deps}
   {:keys [route-name] :as route}]
  (let [default-interceptors [sentry/interceptor]]
    (->> (case route-name
           :home       [{:name ::home :enter #(ok-html! % (render-home/home))}]
           :show-build [(show-build build-tracker)]
           :all-builds [(all-builds build-tracker)]

           :ping          [{:name ::pong :enter #(ok-html! % "pong")}]
           :request-build [(body/body-params) request-build-validate (request-build deps)]
           :full-build    [(body/body-params) (full-build deps)]
           :circle-ci-webhook [(body/body-params) (circle-ci-webhook deps)]

           :group/index        (view grimoire-store route-name)
           :artifact/index     (view grimoire-store route-name)
           :artifact/version   (view grimoire-store route-name)
           :artifact/namespace (view grimoire-store route-name)
           :artifact/doc       (view grimoire-store route-name)

           :jump-to-project    [(jump-interceptor)]
           :badge-for-project  [(badge-interceptor)])
         (into default-interceptors)
         (assoc route :interceptors))))

(defmethod ig/init-key :cljdoc/pedestal [_ opts]
  (log/info "Starting pedestal on port" (:port opts))
  ;; For some reason passing the Grimoire store as a key in the opts map
  ;; doesn't work, arrives as the following:
  ;; (:grimoire.api.fs/Config {:docs "data/grimoire", :examples "", :notes ""})
  (let [grimoire-store (cljdoc.grimoire-helpers/grimoire-store
                        (clojure.java.io/file (:dir opts) "grimoire"))
        deps (assoc opts :grimoire-store grimoire-store)]
    (-> {::http/routes (routes/routes (partial route-resolver deps) {})
         ::http/type   :jetty
         ::http/join?  false
         ::http/port   (:port opts)
         ;; TODO look into this somre more:
         ;; - https://groups.google.com/forum/#!topic/pedestal-users/caRnQyUOHWA
         ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}
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
