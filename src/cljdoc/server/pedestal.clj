(ns cljdoc.server.pedestal
  "Weaves together the various HTTP components of cljdoc.

  Routing and HTTP concerns are handled via Pedestal and
  endpoints are implemented as [intereceptors](http://pedestal.io/reference/interceptors).
  For more details on routing see [[cljdoc.server.routes]].

  The main aspects handlded via HTTP (and thus through this namespace) are:

  - Rendering documentation pages (see [[view]])
  - Rendering build logs (see [[show-build]] & [[all-builds]])
  - Rendering a sitemap (see [[sitemap-interceptor]])
  - Handling build requests (see [[request-build]], [[full-build]] & [[circle-ci-webhook]])
  - Redirecting to newer releases (see [[version-resolve-redirect]] & [[jump-interceptor]])"
  (:require [cljdoc.render.build-req :as render-build-req]
            [cljdoc.render.build-log :as render-build-log]
            [cljdoc.render.index-pages :as index-pages]
            [cljdoc.render.home :as render-home]
            [cljdoc.render.search :as search]
            [cljdoc.render.meta :as render-meta]
            [cljdoc.render.error :as error]
            [cljdoc.render.offline :as offline]
            [cljdoc.renderers.html :as html]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.pedestal-util :as pu]
            [cljdoc.server.routes :as routes]
            [cljdoc.server.api :as api]
            [cljdoc.server.sitemap :as sitemap]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.storage.api :as storage]
            [cljdoc.util :as util]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.sentry :as sentry]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [cognician.dogstatsd :as d]
            [co.deps.ring-etag-middleware :as etag]
            [integrant.core :as ig]
            [cheshire.core :as json]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]))

(def render-interceptor
  "This interceptor will render the documentation page for the current route
  based on the cache-bundle that has been injected into the context previously
  by the [[artifact-data-loader]] interceptor.

  If the request is for the root page (e.g. /d/group/artifact/0.1.0) this interceptor
  will also lookup the first article that's part of the cache-bundle and return a 302
  redirecting to that page."
  {:name  ::render
   :enter (fn render-doc [{:keys [cache-bundle] :as ctx}]
            (let [path-params (-> ctx :request :path-params)
                  page-type   (-> ctx :route :route-name)]
              (if-let [first-article-slug (and (= page-type :artifact/version)
                                               (-> cache-bundle :cache-contents :version :doc first :attrs :slug))]
                ;; instead of rendering a mostly white page we
                ;; redirect to the README/first listed article
                (let [location (routes/url-for :artifact/doc :params (assoc path-params :article-slug first-article-slug))]
                  (assoc ctx :response {:status 302, :headers {"Location" location}}))

                (if cache-bundle
                  (d/measure! "cljdoc.views.render_time" {}
                              (pu/ok-html ctx (html/render page-type path-params cache-bundle)))
                  (let [resp {:status 404
                              :headers {"Content-Type" "text/html"}
                              :body (str (render-build-req/request-build-page path-params))}]
                    (assoc ctx :response resp))))))})

(def doc-slug-parser
  "Further process the `article-slug` URL segment by splitting on `/` characters.

  This is necessary because we want to allow arbitrary nesting in user
  provided doctrees and Pedestal's router will return a single string
  for everything that comes after a wildcard path segment."
  {:name ::doc-slug-parser
   :enter (fn [ctx]
            (->> (string/split (get-in ctx [:request :path-params :article-slug])  #"/")
                 ;; I feel like pedestal should take care of this url-decoding
                 ;; https://github.com/cljdoc/cljdoc/issues/113
                 (map #(java.net.URLDecoder/decode % "UTF-8"))
                 (assoc-in ctx [:request :path-params :doc-slug-path])))})

(defn index-page
  "Return a list of interceptors suitable to render an group or
  artifact index page as specified by `render-fn`."
  [store render-fn]
  [{:name ::releases-loader
    :enter (fn releases-loader-inner [ctx]
             (assoc ctx ::releases (storage/list-versions store (-> ctx :request :path-params :group-id))))}
   (pu/html #(render-fn (-> % :request :path-params) (::releases %)))])

(defn artifact-data-loader
  "Return an interceptor that loads all data from `store` that is
  relevant for the artifact identified via the entity map in `:path-params`."
  [store]
  {:name  ::artifact-data-loader
   :enter (fn artifact-data-loader-inner [ctx]
            (let [params (-> ctx :request :path-params)]
              (d/measure! "cljdoc.storage.read_time" {}
                          (log/info "Loading artifact cache bundle for" params)
                          (if (storage/exists? store params)
                            (assoc ctx :cache-bundle (storage/bundle-docs store params))
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

(defn view
  "Combine various interceptors into an interceptor chain for
  rendering views for `route-name`."
  [storage route-name]
  (->> [(version-resolve-redirect)
        (when (= :artifact/doc route-name) doc-slug-parser)
        (artifact-data-loader storage)
        render-interceptor]
       (keep identity)
       (vec)))

(defn redirect-to-build-page
  [ctx build-id]
  {:pre [(some? build-id)]}
  (assoc ctx :response {:status 303 :headers {"Location" (str "/builds/" build-id)}}))

(defn request-build
  "Create an interceptor that will initiate documentation builds based
  on provided form params using `analysis-service` for analysis and tracking
  build progress/state via `build-tracker`."
  [{:keys [analysis-service build-tracker] :as deps}]
  {:name ::request-build
   :enter (fn request-build-handler [ctx]
            (if-let [running (build-log/running-build build-tracker
                                                      (-> ctx :request :form-params :project util/group-id)
                                                      (-> ctx :request :form-params :project util/artifact-id)
                                                      (-> ctx :request :form-params :version))]
              (redirect-to-build-page ctx (:id running))
              (let [build (api/kick-off-build!
                           deps
                           {:project (-> ctx :request :form-params :project)
                            :version (-> ctx :request :form-params :version)})]
                (redirect-to-build-page ctx (:build-id build)))))})

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
              (if (= "text/html" (pu/accepted-type ctx))
                (pu/ok ctx (cljdoc.render.build-log/build-page build-info))
                (pu/ok ctx build-info))
              ;; Not setting :response implies 404 response
              ctx))})

(defn all-builds
  [build-tracker]
  {:name ::build-index
   :enter (fn build-index-render [ctx]
            (->> (build-log/recent-builds build-tracker 30)
                 (cljdoc.render.build-log/builds-page)
                 (pu/ok-html ctx)))})

(defn badge-url [status color]
  (format "https://badgen.now.sh/badge/cljdoc/%s/%s"
          (string/replace status  #"/" "%2F")
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
            (let [{:keys [project artifact-id group-id] :as params} (-> ctx :request :path-params)
                  project (cond project project
                                artifact-id (util/clojars-id params)
                                group-id group-id)
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

(def etag-interceptor
  {:name ::etag
   :leave (ring-middlewares/response-fn-adapter
           (fn [request opts]
             (etag/add-file-etag request false)))})

(def not-found-interceptor
  {:name ::not-found-interceptor
   :leave (fn [context]
            (if-not (http/response? (:response context))
              (assoc context :response {:status 404
                                        :headers {"Content-Type" "text/html"}
                                        :body (error/not-found-404)})
              context))})

(defn build-sitemap
  "Build a new sitemap if previous one was built longer than 60 minutes ago."
  [{:keys [last-generated sitemap] :as state} storage]
  (let [now (java.util.Date.)]
    (if (or (not last-generated)
            (> (- (.getTime now) (.getTime last-generated)) (* 60 60 1000)))
      ;; Return updated state
      {:last-generated now :sitemap (sitemap/build storage)}
      ;; Return identical state
      state)))

(defn sitemap-interceptor
  [storage]
  (let [state (atom {})]
    {:name  ::sitemap
     :enter #(pu/ok-xml % (:sitemap (swap! state build-sitemap storage)))}))

(def offline-bundle
  "Creates an HTTP response with a zip file containing offline docs
  for the project that has been injected into the context by [[artifact-data-loader]]."
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
  "Given a route name return a list of interceptors to handle requests
  to that route.

  This has been put into place to better separate route-definitions
  from handler implementation in case route-definitions become
  interesting for ClojureScript where Pededestal can't go.

  For more details see `cljdoc.server.routes`."
  [{:keys [build-tracker storage] :as deps}
   {:keys [route-name] :as route}]
  (->> (case route-name
         :home       [{:name ::home :enter #(pu/ok-html % (render-home/home))}]
         :search     [{:name ::search :enter #(pu/ok-html % (search/search-page %))}]
         :suggest    [{:name ::suggest :enter search/suggest-api}]
         :shortcuts  [{:name ::shortcuts :enter #(pu/ok-html % (render-meta/shortcuts))}]
         :sitemap    [(sitemap-interceptor storage)]
         :show-build [pu/coerce-body
                      (pu/negotiate-content #{"text/html" "application/edn" "application/json"})
                      (show-build build-tracker)]
         :all-builds [(all-builds build-tracker)]

         :ping          [{:name ::pong :enter #(pu/ok-html % "pong")}]
         :request-build [(body/body-params) request-build-validate (request-build deps)]

         :group/index     (index-page storage index-pages/group-index)
         :artifact/index  (index-page storage index-pages/artifact-index)

         :artifact/version   (view storage route-name)
         :artifact/namespace (view storage route-name)
         :artifact/doc       (view storage route-name)
         :artifact/offline-bundle [(artifact-data-loader storage)
                                   offline-bundle]

         :artifact/current-via-short-id [(jump-interceptor)]
         :artifact/current [(jump-interceptor)]
         :jump-to-project    [(jump-interceptor)]
         :badge-for-project  [(badge-interceptor)])
       (assoc route :interceptors)))

(defmethod ig/init-key :cljdoc/pedestal [_ opts]
  (log/info "Starting pedestal on port" (:port opts))
  (-> {::http/routes (routes/routes (partial route-resolver opts) {})
       ::http/type   :jetty
       ::http/join?  false
       ::http/port   (:port opts)
       ;; TODO look into this some more:
       ;; - https://groups.google.com/forum/#!topic/pedestal-users/caRnQyUOHWA
       ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}
       ::http/resource-path "public"
       ::http/not-found-interceptor not-found-interceptor}
      http/default-interceptors
      (update ::http/interceptors #(into [sentry/interceptor etag-interceptor] %))
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :cljdoc/pedestal [_ server]
  (http/stop server))

(comment

  (def s (http/start (create-server (routes {}))))

  (http/stop s)

  (require 'io.pedestal.test)

  (io.pedestal.test/response-for (:io.pedestal.http/service-fn s) :post "/api/request-build2")

  )
