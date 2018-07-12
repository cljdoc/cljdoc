(ns cljdoc.server.pedestal
  (:require [cljdoc.render.build-req :as render-build-req]
            [cljdoc.render.build-log :as render-build-log]
            [cljdoc.render.home :as render-home]
            [cljdoc.render.offline :as offline]
            [cljdoc.renderers.html :as html]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.pedestal-util :as pu]
            [cljdoc.server.routes :as routes]
            [cljdoc.server.api :as api]
            [cljdoc.storage.api :as storage]
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
            (case route-name
              (:group/index :artifact/index)
              (do (log/info "Loading group cache bundle for" (:path-params request))
                  (assoc ctx :cache-bundle (storage/bundle-group store (:path-params request))))

              (:artifact/version :artifact/doc :artifact/namespace :artifact/offline-bundle)
              (do (log/info "Loading artifact cache bundle for" (:path-params request))
                  (if (storage/exists? store (:path-params request))
                    (assoc ctx :cache-bundle (storage/bundle-docs store (:path-params request)))
                    ctx))))})

(defn- resolve-version [path-params referer]
  (assert (= "CURRENT" (:version path-params)))
  (->> (or (some-> referer util/uri-path routes/match-route :path-params :version)
           (repos/latest-release-version (str (-> path-params :group-id) "/"
                                              (-> path-params :artifact-id))))
       (assoc path-params :version)))

(defn version-resolve-redirect
  "Intelligently resolve the `CURRENT` version based on the referer
  and Clojars releases.

  Users may want to link to API docs from their existing non-API
  (Markdown/Asciidoc) documentation. Since links are usually tied to a
  specific version this can become cumbersome when updating docs.
  This interceptor intelligently rewrites any usages of `CURRENT` in
  the version part of the URL to use either

  - the version from the referring URL
  - the version of the last release from Clojars"
  []
  {:name ::version-resolve-redirect
   :enter (fn [{:keys [request route] :as ctx}]
            (cond-> ctx
              (= "CURRENT" (-> request :path-params :version))
              (assoc :response
                     {:status 307
                      :headers {"Location" (->> (get-in request [:headers "referer"])
                                                (resolve-version (:path-params request))
                                                (routes/url-for (:route-name route) :path-params))}})))})

(def article-locator
  {:name ::article-locator
   :enter (fn article-locator [ctx]
            ;; TOOD read correct article from cache-bundle, put
            ;; somewhere in ctx if not found 404
            )})

(defn view [storage route-name]
  (->> [(version-resolve-redirect)
        (when (= :artifact/doc route-name) doc-slug-parser)
        (grimoire-loader storage route-name)
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
  [{:keys [storage build-tracker]}]
  {:name ::full-build
   :enter (fn [ctx]
            (api/full-build
             {:storage       storage
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
              (if (= "text/html" (get-in ctx [:request :accept :field]))
                (ok! ctx (cljdoc.render.build-log/build-page build-info))
                (ok! ctx build-info))
              ;; Not setting :response implies 404 response
              ctx))})

(defn all-builds
  [build-tracker]
  {:name ::build-index
   :enter (fn build-index-render [ctx]
            (->> (build-log/recent-builds build-tracker 100)
                 (cljdoc.render.build-log/builds-page)
                 (ok-html! ctx)))})

(defn badge-url [status color]
  (format "https://img.shields.io/badge/cljdoc-%s-%s.svg"
          (-> status (string/replace #"-" "--") (string/replace #"/" "%2F"))
          (name color)))

(defn badge-interceptor []
  {:name ::badge
   :enter (fn badge [ctx]
            (log/info "Badge req headers" (-> ctx :request :headers))
            (let [project (-> ctx :request :path-params :project)
                  release (try (repos/latest-release-version project)
                               (catch Exception e
                                 (log/warnf "Could not find release for %s" project)))
                  url     (if release
                            (badge-url release :blue)
                            (badge-url (str "no release found for " project) :red))
                  badge   (clj-http.lite.client/get url {:headers {"User-Agent" "clj-http-lite"}})]
              (->> {:status 200
                    :headers {"Content-Type" "image/svg+xml;charset=utf-8"
                              "Cache-Control" (format "public; max-age=%s" (* 30 60))}
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

(defn offline-bundle []
  {:name ::offline-bundle
   :enter (fn offline-bundle [{:keys [cache-bundle] :as ctx}]
            (log/info "Bundling" (str (-> cache-bundle :cache-id :artifact-id) "-"
                                      (-> cache-bundle :cache-id :version) ".zip"))
            (->> (if cache-bundle
                   {:status 200
                    :headers {"Content-Type" "application/zip, application/octet-stream"
                              "Content-Disposition" (format "attachment; filename=\"%s\""
                                                            (str (-> cache-bundle :cache-id :artifact-id) "-"
                                                                 (-> cache-bundle :cache-id :version) ".zip"))}
                    :body (offline/zip-stream cache-bundle)}
                   {:status 404
                    :headers {}
                    :body "Could not find data, please request a build first"})
                 (assoc ctx :response)))})

(defn route-resolver
  [{:keys [build-tracker storage] :as deps}
   {:keys [route-name] :as route}]
  (let [default-interceptors [sentry/interceptor]]
    (->> (case route-name
           :home       [{:name ::home :enter #(ok-html! % (render-home/home))}]
           :show-build [pu/coerce-body
                        (pu/negotiate-content #{"text/html" "application/edn" "application/json"})
                        (show-build build-tracker)]
           :all-builds [(all-builds build-tracker)]

           :ping          [{:name ::pong :enter #(ok-html! % "pong")}]
           :request-build [(body/body-params) request-build-validate (request-build deps)]
           :full-build    [(body/body-params) (full-build deps)]
           :circle-ci-webhook [(body/body-params) (circle-ci-webhook deps)]

           :group/index        (view storage route-name)
           :artifact/index     (view storage route-name)
           :artifact/version   (view storage route-name)
           :artifact/namespace (view storage route-name)
           :artifact/doc       (view storage route-name)
           :artifact/offline-bundle [(grimoire-loader storage route-name)
                                     (offline-bundle)]
           :jump-to-project    [(jump-interceptor)]
           :badge-for-project  [(badge-interceptor)])
         (into default-interceptors)
         (assoc route :interceptors))))

(defmethod ig/init-key :cljdoc/pedestal [_ opts]
  (log/info "Starting pedestal on port" (:port opts))
  (-> {::http/routes (routes/routes (partial route-resolver opts) {})
       ::http/type   :jetty
       ::http/join?  false
       ::http/port   (:port opts)
       ;; TODO look into this somre more:
       ;; - https://groups.google.com/forum/#!topic/pedestal-users/caRnQyUOHWA
       ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}
       ;; When runnning with boot repl the resources on the classpath are not
       ;; updated as they change so in these cases using file path is easier
       ;; This breaks the homepage for whatever reason
       ;; ::http/file-path "resources/public"
       ::http/resource-path "public"}
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :cljdoc/pedestal [_ server]
  (http/stop server))

(comment

  (def s (http/start (create-server (routes {}))))

  (http/stop s)

  (clojure.pprint/pprint
   (routes {:grimoire-store gs}))

  (require 'io.pedestal.test)

  (io.pedestal.test/response-for (:io.pedestal.http/service-fn s) :post "/api/request-build2")

  )
