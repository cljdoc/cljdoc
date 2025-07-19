(ns cljdoc.server.routes
  "Pedestals routing is pretty nice but tying the routing table too much
  to the handlers can be annoying when trying to generate routes outside
  of a Pedestal server (e.g. when rendering files statically.)

  This namespace lists all routes of cljdoc and exposes some utility
  functions to generate URLs given the routing information.

  With some more work this could probably also be used from ClojureScript.

  For use with http handlers a `route-resolver` can be passed when
  generating all routes. See docstring of `routes` for details."
  (:require
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :as interceptor]
   [io.pedestal.service.protocols :as sp]
   ;; TODO: Temp fix for bug?
   [io.pedestal.service.resources :as resources])
  (:import [jakarta.servlet.http HttpServletResponse]))


;; TODO: Temp fix for bug?
(extend-protocol sp/ResponseBufferSize
  HttpServletResponse
  (response-buffer-size [response]
    (.getBufferSize response)))


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

;; TODO: In 0.8.0 I think we can have :interceptor in input route table??
;; So might be able to resolve before expanding?
(defn routes
  "Return the expanded routes given the `opts` as passed to
  `io.pedestal.http.route/expand-routes`. The `route-resolver` will be
  used for post processing the routes, usually setting the right
  interceptors."
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
        ;; TODO: Seems awkward, this built-in includes interceptor, and addition is... blech
        (update :routes #(into % (:routes (resources/resource-routes {:resource-root "public/out"})) )))))

(comment
  (conj nil 3 )

  (routes identity {})



  :eoc)

(defn- url-for-routes
  "A variant of Pedestal's own url-for-routes but instead of
  accepting path-params maps with missing parameters this one throws.

  See https://github.com/pedestal/pedestal/issues/572"
  ;; NOTE: pedestal has this support now with :strict-path-params? on its url-for-routes
  [routing-table & default-options]
  (let [routes (:routes routing-table)
        {:as default-opts} default-options
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
  ;; => "/d/a/b/c"

  (url-for :artifact/version :path-params {:group-id "a" :artifact-id "b"})
  ;; => Execution error (ExceptionInfo) at cljdoc.server.routes$url_for_routes$fn__57100/doInvoke (routes.clj:118).
  ;;    Missing path-param :version

  (foo :artifact/version :path-params {:group-id "a" :artifact-id "b"})
  ;; => "/d/a/b/:version"

  (match-route "/d/foo/bar/CURRENT")
  ;; => {:path "/d/:group-id/:artifact-id/:version",
  ;;     :method :get,
  ;;     :path-constraints
  ;;     {:group-id "([^/]+)", :artifact-id "([^/]+)", :version "([^/]+)"},
  ;;     :path-re #"/\Qd\E/([^/]+)/([^/]+)/([^/]+)",
  ;;     :path-parts ["d" :group-id :artifact-id :version],
  ;;     :interceptors
  ;;     [{:name :cljdoc.server.routes/identity-interceptor,
  ;;       :enter #function[clojure.core/identity],
  ;;       :leave nil,
  ;;       :error nil}],
  ;;     :route-name :artifact/version,
  ;;     :path-params {:group-id "foo", :artifact-id "bar", :version "CURRENT"},
  ;;     :io.pedestal.http.route.internal/satisfies-constraints?
  ;;     #function[io.pedestal.http.route.internal/add-satisfies-constraints?/fn--23386]}

  (clojure.pprint/pprint
   (routes identity {}))

  :eoc)
