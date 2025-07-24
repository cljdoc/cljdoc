(ns cljdoc.server.routes
  "By default, pedestal ties routing to handlers/interceptors.

  It is a awkward to go against the pedestal-way, but we decoupled routes
  from handlers.

  One big plus of decoupling is that it allows us to expose a `url-for` a given route + params
  for use by our renderers. (There is a interceptor-scoped `url-for` in the pedestal API,
  but it was a bit too magic for our taste).

  Routes can be optionally hooked up to handlers by the `route-resolver` passed into `routes`."
  (:require
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :as interceptor]))

;; Pedestal requires an interceptor to be associated with a route, so we
;; specify a no-op interceptor to appease it. This dummy interceptor can
;; be replaced by the `route-resolver` passed into `routes`.
(def ^:private nop
  (interceptor/interceptor
   {:name ::identity-interceptor
    :enter identity}))

(defn api-routes []
  #{["/api/ping" :get nop :route-name :ping]
    ["/api/request-build2" :post nop :route-name :request-build]
    ["/api/search" :get nop :route-name :api/search]
    ["/api/search-suggest" :get nop :route-name :api/search-suggest]
    ["/api/searchset/:group-id/:artifact-id/:version" :get nop :route-name :api/searchset]
    ["/api/server-info" :get nop :route-name :api/server-info]
    ["/experiments/cora/api/docsets/:group-id/:artifact-id/:version" :get nop :route-name :api/docsets]})

(defn build-log-routes []
  #{["/builds/:id" :get nop :route-name :show-build]
    ["/builds" :get nop :route-name :builds-summary]})

(defn documentation-routes []
  ;; param :group-id of first route is a bit misleading
  ;; see https://github.com/pedestal/pedestal/issues/337
  #{["/d/:group-id" :get nop :route-name :artifact/current-via-short-id]
    ["/d/:group-id/:artifact-id" :get nop :route-name :artifact/current]
    ["/d/:group-id/:artifact-id/:version" :get nop :route-name :artifact/version]
    ["/d/:group-id/:artifact-id/:version/doc/*article-slug" :get nop :route-name :artifact/doc]
    ["/d/:group-id/:artifact-id/:version/api/:namespace" :get nop :route-name :artifact/namespace]
    ["/download/:group-id/:artifact-id/:version" :get nop :route-name :artifact/offline-bundle]})

(defn index-routes []
  #{["/versions" :get nop :route-name :cljdoc/index]
    ["/versions/:group-id" :get nop :route-name :group/index]
    ["/versions/:group-id/:artifact-id" :get nop :route-name :artifact/index]})

(defn open-search-routes []
  #{["/search" :get nop :route-name :search]})

(defn info-pages-routes []
  #{["/" :get nop :route-name :home]
    ["/shortcuts" :get nop :route-name :shortcuts]
    ["/sitemap.xml" :get nop :route-name :sitemap]
    ["/opensearch.xml" :get nop :route-name :opensearch]})

(defn utility-routes []
  #{["/jump/release/*project" :get nop :route-name :jump-to-project]
    ["/badge/*project"
     :get nop
     :route-name :badge-for-project
     ;; Ensure at most one slash.
     ;; See https://github.com/cljdoc/cljdoc/issues/348
     :constraints {:project #"^[^/]+(/[^/]+)?$"}]})

(defn routes
  "Return the expanded routes given the `opts` as passed to
  `io.pedestal.http.route/expand-routes`.

  The `route-resolver` is used to post process the routes, and typically
  assigns interceptors to the routes."
  [route-resolver {:keys [host] :as opts}]
  (->> [(when host
          ;; https://github.com/pedestal/pedestal/issues/570
          #{(select-keys opts [:host :port :scheme])})
        (documentation-routes)
        (index-routes)
        (open-search-routes)
        (api-routes)
        (build-log-routes)
        (info-pages-routes)
        (utility-routes)]
       (reduce into #{})
       (route/expand-routes)
       (keep route-resolver)))

(defn- url-for-routes
  "A variant of Pedestal's `url-for-routes` that throws when there is a missing
  path-param.

  Pedestal's `url-for-routes` now has a `:strict-path-params?` that will also throw,
  but we'll stick with our version as we appriciate the reporting of the missing key."
  [routes & default-options]
  (let [{:as default-opts} default-options
        m (#'route/linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            default-app-name (:app-name default-opts)
            route (#'route/find-route m (or app-name default-app-name) route-name)
            opts (#'route/combine-opts options-map default-opts route)]
        (doseq [k (:path-params route)]
          (when-not (get-in opts [:path-params k])
            (throw (ex-info (format "Missing path-param %s" k)
                            {:route-path (:path route) :route-name (:route-name route) :opts opts}))))
        (#'route/link-str route opts)))))

(def url-for
  (url-for-routes (routes identity {})))

(defn match-route [path-info]
  (route/try-routing-for (routes identity {}) :map-tree path-info :get))

(comment
  (url-for :artifact/version :path-params {:group-id "a" :artifact-id "b" :version "c"})

  (match-route "/d/foo/bar/CURRENT")

  (clojure.pprint/pprint
   (routes identity {})))
