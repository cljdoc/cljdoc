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
   [io.pedestal.interceptor :as interceptor]
   [io.pedestal.service.resources :as resources]))

;; Pedestal requires that each route have an interceptor, this no-op interceptor
;; will be replaced when routes are resolved
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
    ["/download/:group-id/:artifact-id/:version" :get nop :route-name :artifact/offline-docset]})

(defn index-routes []
  #{["/versions" :get nop :route-name :cljdoc/index]
    ["/versions/:group-id" :get nop :route-name :group/index]
    ["/versions/:group-id/:artifact-id" :get nop :route-name :artifact/index]})

(defn open-search-routes []
  #{["/search" :get nop :route-name :search]})

(defn info-pages-routes []
  #{["/" :get nop :route-name :home]
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
  (let [routes (->> [(when host
                       ;; TODO: Turf?
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
                    (route/expand-routes))]
    (-> routes
        (update :routes #(keep route-resolver %))
        (update :routes #(into % (:routes (resources/resource-routes {:resource-root "public/out"})))))))

(defn- url-for-routes
  "Customized version of pedestal's url-for-routes.

  Their version supports `:strict-path-params?` but it does not do what we want.
  Our version here will throw when there are missing params but is OK with extra params.
  We also throw with which params are missing."
  [routing-table & default-options]
  {:pre []}
  (let [routes (:routes routing-table)
        {:as default-opts} default-options
        m      (#'route/linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            default-app-name (:app-name default-opts)
            route            (#'route/find-route m (or app-name default-app-name) route-name)
            opts             (#'route/combine-opts options-map default-opts route)
            missing-params (reduce (fn [acc k]
                                     (if-not (get-in opts [:path-params k])
                                       (conj acc k)
                                       acc))
                                   []
                                   (:path-params route))]

        (when (seq missing-params)
          (throw (ex-info (format "Missing path-params: %s" missing-params)
                          {:route-path (:path route)
                           :route-name (:route-name route)
                           :opts opts})))
        (#'route/link-str route opts)))))

(def url-for
  (url-for-routes (routes identity {})))

(defn match-route [path-info]
  (route/try-routing-for (routes identity {}) path-info :get))

(comment
  (url-for :artifact/index :path-params {:group-id "g" :artifact-id "a"})
  ;; => "/versions/g/a"

  (url-for :artifact/version :path-params {:group-id "a" :artifact-id "b" :version "c"})
  ;; => "/d/a/b/c"

  (url-for :artifact/version :path-params {:group-id "a" :artifact-id "b"})
  ;; => Execution error (ExceptionInfo) at cljdoc.server.routes$url_for_routes$fn__62949/doInvoke (routes.clj:119).
  ;;    Missing path-params [:version]

  (match-route "/d/foo/bar/CURRENT")
  ;; => {:path "/d/:group-id/:artifact-id/:version",
  ;;     :method :get,
  ;;     :path-constraints
  ;;     {:group-id "([^/]+)", :artifact-id "([^/]+)", :version "([^/]+)"},
  ;;     :path-parts ["d" :group-id :artifact-id :version],
  ;;     :interceptors
  ;;     [{:name :cljdoc.server.routes/identity-interceptor,
  ;;       :enter #function[clojure.core/identity],
  ;;       :leave nil,
  ;;       :error nil}],
  ;;     :route-name :artifact/version,
  ;;     :path-params {:group-id "foo", :artifact-id "bar", :version "CURRENT"},
  ;;     :io.pedestal.http.route.internal/satisfies-constraints?
  ;;     #function[io.pedestal.http.route.internal/add-satisfies-constraints?/fn--22131]}

  (clojure.pprint/pprint
   (routes identity {}))

  :eoc)
