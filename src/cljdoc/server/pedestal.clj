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
  - Redirecting to newer releases (see [[resolve-version-interceptor]] & [[jump-interceptor]])"
  (:require [cheshire.core :as json]
            [cljdoc.render.api-searchset :as api-searchset]
            [cljdoc.render.build-req :as render-build-req]
            [cljdoc.render.build-log :as render-build-log]
            [cljdoc.render.index-pages :as index-pages]
            [cljdoc.render.home :as render-home]
            [cljdoc.render.search :as render-search]
            [cljdoc.render.meta :as render-meta]
            [cljdoc.render.error :as error]
            [cljdoc.render.offline :as offline]
            [cljdoc.render :as html]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.pedestal-util :as pu]
            [cljdoc.server.routes :as routes]
            [cljdoc.server.api :as api]
            [cljdoc.server.search.api :as search-api]
            [cljdoc.server.sitemap :as sitemap]
            [cljdoc.storage.api :as storage]
            [cljdoc-shared.pom :as pom]
            [cljdoc-shared.proj :as proj]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.sentry :as sentry]
            [clojure.core.memoize :as memoize]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as string]
            [clj-http.lite.client :as http-client]
            [co.deps.ring-etag-middleware :as etag]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as plog]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [ring.util.codec :as ring-codec]
            [lambdaisland.uri.normalize :as normalize]
            [net.cgrand.enlive-html :as en])
  (:import (java.io IOException)
           (java.net URLDecoder)
           (java.util Date)))

(def render-interceptor
  "This interceptor will render the documentation page for the current route
  based on the cache-bundle that has been injected into the context previously
  by the [[artifact-data-loader]] interceptor.

  If the request is for the root page (e.g. /d/group/artifact/0.1.0) this interceptor
  will also lookup the first article that's part of the cache-bundle and return a 302
  redirecting to that page."
  (interceptor/interceptor
   {:name  ::render
    :enter (fn render-doc [{:keys [cache-bundle] :as ctx}]
             (let [pp (get-in ctx [:request :path-params])
                   path-params
                   (cond-> pp
                     ;; fixes https://github.com/cljdoc/cljdoc/issues/373
                     (string? (:namespace pp))
                     (update :namespace normalize/percent-decode))
                   page-type   (-> ctx :route :route-name)
                   first-article-slug (and (= page-type :artifact/version)
                                           (-> cache-bundle :version :doc first :attrs :slug))]
               (cond
                 (not (::pom-info ctx))
                 (assoc ctx :response {:status 404
                                       :headers {"Content-Type" "text/html"}
                                       :body (error/not-found-artifact (:static-resources ctx) pp)})

                 (not cache-bundle)
                 (let [resp {:status 404
                             :headers {"Content-Type" "text/html"}
                             :body (str (render-build-req/request-build-page path-params (:static-resources ctx)))}]
                   (assoc ctx :response resp))

                 first-article-slug
                 ;; instead of rendering a mostly white page we
                 ;; redirect to the README/first listed article
                 (let [location (routes/url-for :artifact/doc :params (assoc path-params :article-slug first-article-slug))]
                   (assoc ctx :response {:status 302, :headers {"Location" location}}))

                 :else
                 (pu/ok-html ctx (html/render page-type path-params {:cache-bundle cache-bundle
                                                                     :pom (::pom-info ctx)
                                                                     :last-build (::last-build ctx)
                                                                     :static-resources (:static-resources ctx)})))))}))

(def doc-slug-parser
  "Further process the `article-slug` URL segment by splitting on `/` characters.

  This is necessary because we want to allow arbitrary nesting in user
  provided doctrees and Pedestal's router will return a single string
  for everything that comes after a wildcard path segment."
  (interceptor/interceptor
   {:name ::doc-slug-parser
    :enter (fn [ctx]
             (->> (string/split (get-in ctx [:request :path-params :article-slug])  #"/")
                  ;; I feel like pedestal should take care of this url-decoding
                  ;; https://github.com/cljdoc/cljdoc/issues/113
                  (map #(URLDecoder/decode ^String % "UTF-8"))
                  (assoc-in ctx [:request :path-params :doc-slug-path])))}))

(defn available-docs-denormalized
  "Return all available documents (built or not) with one item per version
  (i.e. in the same format as expected by index-pages/versions-tree)"
  [searcher {:keys [group-id artifact-id] :as artifact-ent}]
  (->> (search-api/artifact-versions searcher artifact-ent)
       (mapcat (fn [artifact]
                 (map
                  #(-> artifact (dissoc :versions) (assoc :version %))
                  (:versions artifact))))))

(defn versions-data
  "Return matching documents with version info in a tree.
  If `:all` `params` specified, returns all artifact non-SNAPSHOT versions we know about from clojars
  and maven central, else returns all artifact versions we've attempted to build.

  The `:all` option is in support of the Dash offline reader."
  [searcher store route-name {{:keys [path-params params]} :request}]
  (let [{:keys [group-id] :as artifact-ent} path-params]
    (->> (if (:all params)
           (available-docs-denormalized searcher artifact-ent)
           (case route-name
             (:artifact/index :group/index)
                        ;; NOTE: We do not filter by artifact-id b/c in the UI version we want
                        ;; to show "Other artifacts under the <XY> group"
                        ;; This is not appropriate for Dash, so we only do this for :built docs
             (storage/list-versions store group-id)

             :cljdoc/index
             (storage/all-distinct-docs store)))
         (index-pages/versions-tree))))

(defn index-pages
  "Return a list of interceptors suitable to render an index page appropriate for the provided `route-name`.
  `route-name` can be either `:artifact/index`,  `:group/index` or `:cljdoc/index`."
  [searcher store route-name]
  [(pu/coerce-body-conf
    (fn html-render-fn [ctx]
      (let [artifact-ent (-> ctx :request :path-params)
            versions-data (-> ctx :response :body)
            static-resources (:static-resources ctx)
            source (if (some-> ctx :request :params :all) :available :built)]
        (case route-name
          :artifact/index (index-pages/artifact-index artifact-ent versions-data source static-resources)
          :group/index (index-pages/group-index artifact-ent versions-data source static-resources)
          :cljdoc/index (index-pages/full-index versions-data source static-resources)))))
   (pu/negotiate-content #{"text/html" "application/edn" "application/json"})
   (interceptor/interceptor
    {:name ::releases-loader
     :enter (fn releases-loader-inner [ctx]
              (pu/ok ctx (versions-data searcher store route-name ctx)))})])

(defn load-cache-bundle
  "Given a `store` (see `:cljdoc/storage` in system.clj), `pom-info` (as returned from
  `load-pom`), and a `version-entity` (see `:cljdoc.spec/version-entity` in spec.cljc),
  load a `cache-bundle` from storage."
  [store pom-info version-entity]
  (let [params (assoc version-entity :dependency-version-entities (:dependencies pom-info))]
    (log/info "Loading artifact cache bundle for" params)
    (when (storage/exists? store params)
      (storage/bundle-docs store params))))

(defn artifact-data-loader
  "Return an interceptor that loads all data from `store` that is
  relevant for the artifact identified via the entity map in `:path-params`."
  [store]
  (interceptor/interceptor
   {:name ::artifact-data-loader
    :enter (fn artifact-data-loader-inner [ctx]
             (if-let [cache-bundle (load-cache-bundle store
                                                      (::pom-info ctx)
                                                      (get-in ctx [:request :path-params]))]
               (assoc ctx :cache-bundle cache-bundle)
               ctx))}))

(defn last-build-loader
  "Load a projects last build into the context"
  [build-tracker]
  (interceptor/interceptor
   {:name ::last-build-loader
    :enter (fn last-build-loader-inner [ctx]
             (let [{:keys [group-id artifact-id version]} (-> ctx :request :path-params)]
               (assoc ctx ::last-build (build-log/last-build build-tracker group-id artifact-id version))))}))

(defn load-pom
  "Given a `fetch-pom-xml` function, one that takes a clojars-id and a version number as parameters, and a
  `version-entity (see `:cljdoc.spec/version-entity` in spec.cljc)`, fetch the pom xml and get the description
  and dependencies."
  [fetch-pom-xml version-entity]
  (when-let [{:keys [artifact-info dependencies]} (some-> (fetch-pom-xml
                                                           (proj/clojars-id version-entity)
                                                           (:version version-entity))
                                                          pom/parse)]
    {:description (:description artifact-info)
     :dependencies dependencies}))

(defn pom-loader
  "Load a projects POM file, parse it and inject some information from it into the context."
  [cache]
  (interceptor/interceptor
   {:name ::pom-loader
    :enter (fn pom-loader-inner [ctx]
             (if-let [pom (load-pom (:cljdoc.util.repositories/get-pom-xml cache)
                                    (get-in ctx [:request :path-params]))]
               (assoc ctx ::pom-info pom)
               ctx))}))

(defn- uri-path
  "Return path part of a URL, this is probably part of pedestal in
  some way but I couldn't find it fast enough. TODO replace."
  [uri]
  (-> uri
      (string/replace #"^https*://" "")
      (string/replace #"^[^/]*" "")))

(defn- matching-referer-version [request target-group-id target-artifact-id ]
  (when-let [{:keys [group-id artifact-id version]}
             (some-> request
                     (get-in [:headers "referer"])
                     uri-path
                     routes/match-route
                     :path-params)]
    (when (and version
               (= target-group-id group-id)
               (= target-artifact-id artifact-id))
      version)))

(def resolve-version-interceptor
  "An interceptor that will look at `:path-params` and try to turn it into an artifact
  entity or redirect of the version is specified in a meta-fasion, i.e. `CURRENT`.

  - If the provided version is `nil` set it to the last known release.
  - If the provided version is `CURRENT` redirect to either a version from the `referer` header
    or the last known version."
  (interceptor/interceptor
   {:name ::resolve-version-interceptor
    :enter (fn resolve-version-interceptor [{:keys [route request] :as ctx}]
             (let [{:keys [project group-id artifact-id version]} (:path-params request)
                   artifact-id     (or artifact-id (proj/artifact-id project))
                   group-id        (or group-id (proj/group-id project))
                   proj-search     (str group-id "/" artifact-id)
                   resolved-version (cond
                                      (nil? version)
                                      (repos/latest-release-version proj-search)

                                      (= "CURRENT" version)
                                      (or (matching-referer-version request group-id artifact-id)
                                          (repos/latest-release-version proj-search))

                                      :else version)
                   artifact-entity {:artifact-id artifact-id
                                    :group-id group-id
                                    :version resolved-version}]
               (cond
                 (nil? resolved-version) ;; not found
                 (assoc ctx :response
                        {:status 404
                         :headers {"Content-Type" "text/html"}
                         :body (error/not-found-artifact (:static-resources ctx)
                                                         {:group-id group-id :artifact-id artifact-id})})

                 (= "CURRENT" version) ;; redirect
                 (assoc ctx :response
                        {:status 307
                         :headers {"Location" (routes/url-for (:route-name route) :path-params (merge (:path-params request) artifact-entity))}})

                 :else
                 (update-in ctx [:request :path-params] merge artifact-entity))))}))

(defn view
  "Combine various interceptors into an interceptor chain for
  rendering views for `route-name`."
  [storage cache build-tracker route-name]
  (->> [resolve-version-interceptor
        (last-build-loader build-tracker)
        (when (= :artifact/doc route-name) doc-slug-parser)
        (pom-loader cache)
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
  [{:keys [build-tracker] :as deps}]
  (interceptor/interceptor
   {:name ::request-build
    :enter (fn request-build-handler [ctx]
             (if-let [running (build-log/running-build build-tracker
                                                       (-> ctx :request :form-params :project proj/group-id)
                                                       (-> ctx :request :form-params :project proj/artifact-id)
                                                       (-> ctx :request :form-params :version))]
               (redirect-to-build-page ctx (:id running))
               (let [build (api/kick-off-build!
                            deps
                            {:project (-> ctx :request :form-params :project)
                             :version (-> ctx :request :form-params :version)})]
                 (redirect-to-build-page ctx (:build-id build)))))}))

(def request-build-validate
  ;; TODO quick and dirty for now
  (interceptor/interceptor
   {:name ::request-build-validate
    :enter (fn request-build-validate [ctx]
             (if (and (some-> ctx :request :form-params :project string?)
                      (some-> ctx :request :form-params :version string?))
               ctx
               (assoc ctx :response {:status 400 :headers {}})))}))

(defn search-interceptor [searcher]
  (interceptor/interceptor
   {:name  ::search
    :enter (fn search-handler [ctx]
             (if-let [q (-> ctx :request :params :q)]
               (pu/ok ctx (search-api/search searcher q))
               (assoc ctx :response {:status 400 :headers {} :body "ERROR: Missing q query param"})))}))

(defn search-suggest-interceptor [searcher]
  (interceptor/interceptor
   {:name  ::search-suggest
    :enter (fn search-suggest-handler [ctx]
             (if-let [q (-> ctx :request :params :q)]
               (assoc ctx :response {:status  200
                                     :body    (search-api/suggest searcher q)})
               (assoc ctx :response {:status 400 :headers {} :body "ERROR: Missing q query param"})))}))

(defn show-build
  [build-tracker]
  (interceptor/interceptor
   {:name ::build-show
    :enter (fn build-show-render [ctx]
             (if-let [build-info (->> ctx :request :path-params :id
                                      (build-log/get-build build-tracker))]
               (pu/ok ctx build-info)
               (assoc ctx :response {:status 404
                                     :headers {"Content-Type" "text/html"}
                                     :body (error/not-found-page (:static-resources ctx))})))}))

(defn all-builds
  [build-tracker]
  (interceptor/interceptor
   {:name ::build-index
    :enter (fn build-index-render [ctx]
             (->> (build-log/recent-builds build-tracker 30)
                  (render-build-log/builds-page ctx)
                  (pu/ok-html ctx)))}))

(defn return-badge
  "Fetch badge svg from badgen.
   Naive retry logic to compensate for fact that badgen.net will often fail on 1st request for uncached badges."
  [ctx status color]
  (let [url (format "https://badgen.net/badge/cljdoc/%s/%s"
                    (ring-codec/url-encode status)
                    (name color))]
    (loop [retries-left 1]
      (let [resp (http-client/get url {:headers {"User-Agent" "clj-http-lite"}
                                       :throw-exceptions false})]
        (cond
          (http-client/unexceptional-status? (:status resp))
          (assoc ctx :response {:status 200
                                :headers {"Content-Type" "image/svg+xml;charset=utf-8"
                                          "Cache-Control" (format "public,max-age=%s" (* 30 60))}
                                :body (:body resp)})

          (> retries-left 0)
          (do
            (log/warnf "Badge service returned %d for url %s, retries left %d" (:status resp) url retries-left)
            (Thread/sleep 300)
            (recur (dec retries-left)))

          :else
          (do
            (log/errorf "Badge service returned %d for url %s after retries, response headers: %s"
                        (:status resp) url (:headers resp))
            (assoc ctx :response {:status 503
                                  :body (str "Badge service error for URL " url)})))))))

(defn badge-interceptor []
  (interceptor/interceptor
   {:name ::badge
    :leave (fn badge [ctx]
             (log/info "Badge req headers" (-> ctx :request :headers))
             (if-let [last-build (::last-build ctx)]
               (let [{:keys [version]} (-> ctx :request :path-params)
                     [status color] (cond
                                      (not (build-log/api-import-successful? last-build))
                                      ["API import failed" :red]

                                      (not (build-log/git-import-successful? last-build))
                                      ["Git import failed" :red]

                                      :else
                                      [version :blue])]
                 (return-badge ctx status color))
               (let [{:keys [project]} (-> ctx :request :path-params)]
                 (assoc ctx :response {:status 404
                                       :body (str "No build found for " project)}))))}))

(defn jump-interceptor []
  (interceptor/interceptor
   {:name ::jump
    :enter (fn jump [ctx]
             (let [{:keys [project artifact-id group-id] :as params} (-> ctx :request :path-params)
                   project (cond project project
                                 artifact-id (proj/clojars-id params)
                                 group-id group-id)
                   release (repos/latest-release-version project)]
               (->> (if release
                      {:status 302
                       :headers {"Location" (routes/url-for :artifact/version
                                                            :params
                                                            {:group-id (proj/group-id project)
                                                             :artifact-id (proj/artifact-id project)
                                                             :version release})}}
                      {:status 404
                       :headers {"Content-Type" "text/html"}
                       :body (error/not-found-artifact (:static-resources ctx)
                                                       {:group-id (proj/group-id project)
                                                        :artifact-id (proj/artifact-id project)})})
                    (assoc ctx :response))))}))

(def etag-interceptor
  (interceptor/interceptor
   {:name ::etag
    :leave (ring-middlewares/response-fn-adapter
            (fn [request _opts]
              (etag/add-file-etag request false)))}))

(def cache-control-interceptor
  (interceptor/interceptor
   {:name  ::cache-control
    :leave (fn [ctx]
             (if-not (get-in ctx [:response :headers "Cache-Control"])
               (if-let [content-type (get-in ctx [:response :headers "Content-Type"])]
                 (let [cacheable-content-type? (fn [content-type]
                                                 (some
                                                  #(contains? #{"text/css" "text/javascript" "image/svg+xml"
                                                                "image/png" "image/x-icon" "text/xml"} %)
                                                  (string/split content-type #";")))]
                   (assoc-in ctx [:response :headers "Cache-Control"]
                             (if (cacheable-content-type? content-type) "max-age=31536000,immutable,public" "no-cache")))
                 ctx)
               ctx))}))

(def build-static-resource-map
  "Extracts all static resource names (content-hashed by Parcel) from the cljdoc.html file.
   Then creates a map that translates the plain resource names to their content-hashed counterparts.
    E.g. /cljdoc.js -> /cljdoc.db58f58a.js"
  (memoize/memo
   (fn [html-path]
     (log/info "building static resource map")
     (let [tags (en/select (en/html-resource html-path) [#{(en/attr? :href) (en/attr? :src)}])]
       (->> tags
            (#(for [tag %] (map (:attrs tag) [:href :src])))
            flatten
            (filter some?)
            (map #(let [[prefix suffix] (string/split % #"[a-z0-9]{8}\.(?!.*\.)")]
                    (when (and prefix suffix)
                      {(str prefix suffix) %})))
            (into {}))))))

(defn clear-static-resource-map-cache! []
  (memoize/memo-clear! build-static-resource-map))

(def static-resource-interceptor
  (interceptor/interceptor
   {:name  ::static-resource
    :enter (fn [ctx]
             (assoc ctx :static-resources (build-static-resource-map "public/out/cljdoc.html")))}))

(def redirect-trailing-slash-interceptor
  ;; Needed because https://github.com/containous/traefik/issues/4247
  (interceptor/interceptor
   {:name ::redirect-trailing-slash
    :leave (fn [ctx]
             (let [uri (-> ctx :request :uri)]
               (cond-> ctx
                 (and (string/ends-with? uri "/")
                      (not= uri "/"))
                 (assoc :response {:status 301
                                   :headers {"Location" (subs uri 0 (dec (count uri)))}}))))}))

(def not-found-interceptor
  (interceptor/interceptor
   {:name ::not-found-interceptor
    :leave (fn [context]
             (if-not (http/response? (:response context))
               (assoc context :response {:status 404
                                         :headers {"Content-Type" "text/html"}
                                         :body (error/not-found-page (:static-resources context))})
               context))}))

(defn build-sitemap
  "Build a new sitemap if previous one was built longer than 60 minutes ago."
  [{:keys [^Date last-generated] :as state} storage]
  (let [now (Date.)]
    (if (or (not last-generated)
            (> (- (.getTime now) (.getTime last-generated)) (* 60 60 1000)))
      ;; Return updated state
      {:last-generated now :sitemap (sitemap/build storage)}
      ;; Return identical state
      state)))

(defn sitemap-interceptor
  [storage]
  (let [state (atom {})]
    (interceptor/interceptor
     {:name  ::sitemap
      :enter #(pu/ok-xml % (:sitemap (swap! state build-sitemap storage)))})))

(defn opensearch
  [opensearch-base-url]
  (interceptor/interceptor
   {:name ::opensearch
    :enter (fn opensearch [ctx]
             (let [template (slurp (io/resource "opensearch.xml"))]
               (->> {:status 200
                     :headers {"Content-Type" "application/opensearchdescription+xml"}
                     :body (string/replace template "{{base.url}}" opensearch-base-url)}
                    (assoc ctx :response))))}))

(def offline-bundle
  "Creates an HTTP response with a zip file containing offline docs
  for the project that has been injected into the context by [[artifact-data-loader]]."
  (interceptor/interceptor
   {:name ::offline-bundle
    :enter (fn offline-bundle [{:keys [cache-bundle static-resources] :as ctx}]
             (log/info "Bundling" (str (-> cache-bundle :version-entity :artifact-id) "-"
                                       (-> cache-bundle :version-entity :version) ".zip"))
             (->> (if cache-bundle
                    {:status 200
                     :headers {"Content-Type" "application/zip, application/octet-stream"
                               "Content-Disposition" (format "attachment; filename=\"%s\""
                                                             (str (-> cache-bundle :version-entity :artifact-id) "-"
                                                                  (-> cache-bundle :version-entity :version) ".zip"))}
                     :body (offline/zip-stream cache-bundle static-resources)}
                    {:status 404
                     :headers {}
                     :body "Could not find data, please request a build first"})
                  (assoc ctx :response)))}))

(def api-searchset
  "Creates an API response with a JSON representation of a searchset."
  (interceptor/interceptor
   {:name ::api-searchset
    :enter (fn api-searchset [{:keys [cache-bundle] :as ctx}]
             (->> (if cache-bundle
                    {:status 200
                     :headers {"Content-Type" "application/json"}
                     :body (json/generate-string (api-searchset/cache-bundle->searchset cache-bundle))}
                    {:status 404
                     :headers {"Content-Type" "application/json"}
                     :body (json/generate-string {:error "Could not find data, please request a build first"})})
                  (assoc ctx :response)))}))

(defn route-resolver
  "Given a route name return a list of interceptors to handle requests
  to that route.

  This has been put into place to better separate route-definitions
  from handler implementation in case route-definitions become
  interesting for ClojureScript where Pededestal can't go.

  For more details see `cljdoc.server.routes`."
  [{:keys [opensearch-base-url build-tracker storage cache searcher] :as deps}
   {:keys [route-name] :as route}]
  (->> (case route-name
         :home       [(interceptor/interceptor {:name ::home :enter #(pu/ok-html % (render-home/home %))})]
         :search     [(interceptor/interceptor {:name ::search :enter #(pu/ok-html % (render-search/search-page %))})]
         :shortcuts  [(interceptor/interceptor {:name ::shortcuts :enter #(pu/ok-html % (render-meta/shortcuts %))})]
         :sitemap    [(sitemap-interceptor storage)]
         :opensearch [(opensearch opensearch-base-url)]
         :show-build [(pu/coerce-body-conf render-build-log/build-page)
                      (pu/negotiate-content #{"text/html" "application/edn" "application/json"})
                      (show-build build-tracker)]
         :all-builds [(all-builds build-tracker)]

         :api/search [pu/coerce-body (pu/negotiate-content #{"application/json"}) (search-interceptor searcher)]
         :api/search-suggest [pu/coerce-body (pu/negotiate-content #{"application/x-suggestions+json"}) (search-suggest-interceptor searcher)]

         :api/searchset [(pom-loader cache)
                         (artifact-data-loader storage)
                         api-searchset]

         :ping          [(interceptor/interceptor {:name ::pong :enter #(pu/ok-html % "pong")})]
         :request-build [(body/body-params) request-build-validate (request-build deps)]

         :cljdoc/index    (index-pages searcher storage route-name)
         :group/index     (index-pages searcher storage route-name)
         :artifact/index  (index-pages searcher storage route-name)

         :artifact/version   (view storage cache build-tracker route-name)
         :artifact/namespace (view storage cache build-tracker route-name)
         :artifact/doc       (view storage cache build-tracker route-name)
         :artifact/offline-bundle [(pom-loader cache)
                                   (artifact-data-loader storage)
                                   offline-bundle]

         :artifact/current-via-short-id [(jump-interceptor)]
         :artifact/current [(jump-interceptor)]
         :jump-to-project    [resolve-version-interceptor
                              (jump-interceptor)]
         :badge-for-project  [(badge-interceptor)
                              resolve-version-interceptor
                              (last-build-loader build-tracker)])
       (assoc route :interceptors)))

;; Hack for filtering out verbose broken pipe error logging.
;; This is just someone clicking x and then y before x is fully deliverd.
;; Credits to tonksy: https://tonsky.me/blog/pedestal/#running-the-app
;; Replace with something more proper when the following is addressed:
;; https://github.com/pedestal/pedestal/issues/623
(defn quieter-error-stylobate [{:keys [servlet-response] :as context} exception]
  (let [cause (stacktrace/root-cause exception)]
    (if (and (instance? IOException cause)
             (= "Broken pipe" (ex-message cause)))
      (log/info "broken pipe")
      (plog/error
       :msg "error-stylobate triggered"
       :exception exception
       :context context))
    (@#'servlet-interceptor/leave-stylobate context)))

; io.pedestal.http.impl.servlet-interceptor/stylobate
(def quieter-stylobate
  (interceptor/interceptor
   {:name ::stylobate
    :enter @#'servlet-interceptor/enter-stylobate
    :leave @#'servlet-interceptor/leave-stylobate
    :error quieter-error-stylobate}))

(defmethod ig/init-key :cljdoc/pedestal [_ opts]
  (log/infof "Starting pedestal on %s:%s" (:host opts) (:port opts))
  (with-redefs [servlet-interceptor/stylobate quieter-stylobate]
    (-> {::http/routes (routes/routes (partial route-resolver opts) {})
         ::http/type   :jetty
         ::http/join?  false
         ::http/port   (:port opts)
         ::http/host   (:host opts)
         ;; TODO look into this some more:
         ;; - https://groups.google.com/forum/#!topic/pedestal-users/caRnQyUOHWA
         ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}
         ::http/resource-path "public/out"
         ::http/not-found-interceptor not-found-interceptor}
        http/default-interceptors
        (update ::http/interceptors #(into [sentry/interceptor
                                            static-resource-interceptor
                                            redirect-trailing-slash-interceptor
                                            (ring-middlewares/not-modified)
                                            etag-interceptor
                                            cache-control-interceptor]
                                           %))
        (http/create-server)
        (http/start))))

(defmethod ig/halt-key! :cljdoc/pedestal [_ server]
  (clear-static-resource-map-cache!)
  (http/stop server))
