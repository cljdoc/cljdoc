(ns cljdoc.server.routes
  "Pedestals routing is pretty nice but tying the routing table too much
  to the handlers can be annoying when trying to generate routes outside
  of a Pedestal server (e.g. when rendering files statically.)

  This namespace lists all routes of cljdoc and exposes some utility
  functions to generate URLs given the routing information.

  With some more work this could probably also be used from ClojureScript.

  For use with http handlers a `route-resolver` can be passed when
  generating all routes. See docstring of `routes` for details."
  (:require [io.pedestal.http.route :as route]))

(def ^:private nop
  {:name ::identity-interceptor
   :enter identity})

(defn api-routes []
  #{["/api/ping" :get nop :route-name :ping]
    ["/api/request-build2" :post nop :route-name :request-build]})

(defn build-log-routes []
  #{["/builds/:id" :get nop :route-name :show-build]
    ["/builds" :get nop :route-name :all-builds]})

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
  #{["/versions/:group-id" :get nop :route-name :group/index]
    ["/versions/:group-id/:artifact-id" :get nop :route-name :artifact/index]})

(defn open-search-routes []
  #{["/search" :get nop :route-name :search]
    ["/suggest" :get nop :route-name :suggest]})

(defn info-pages-routes []
  #{["/" :get nop :route-name :home]
    ["/shortcuts" :get nop :route-name :shortcuts]
    ["/sitemap.xml" :get nop :route-name :sitemap]})

(defn utility-routes []
  #{["/jump/release/*project" :get nop :route-name :jump-to-project]
    ["/badge/*project" :get nop :route-name :badge-for-project]})

(defn routes
  "Return the expanded routes given the `opts` as passed to
  `io.pedestal.http.route/expand-routes`. The `route-resolver` will be
  used for post processing the routes, usually setting the right
  interceptors."
  [route-resolver {:keys [host port scheme] :as opts}]
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
  "A variant of Pedestal's own url-for-routes but instead of
  accepting path-params maps with missing parameters this one throws.

  See https://github.com/pedestal/pedestal/issues/572"
  [routes & default-options]
  (let [{:as default-opts} default-options
        m (#'io.pedestal.http.route/linker-map routes)]
    (fn [route-name & options]
      (let [{:keys [app-name] :as options-map} options
            default-app-name (:app-name default-opts)
            route (#'io.pedestal.http.route/find-route m (or app-name default-app-name) route-name)
            opts (#'io.pedestal.http.route/combine-opts options-map default-opts route)]
        (doseq [k (:path-params route)]
          (when-not (get-in opts [:path-params k])
            (throw (ex-info (format "Missing path-param %s" k)
                            {:route-path (:path route) :route-name (:route-name route) :opts opts}))))
        (#'io.pedestal.http.route/link-str route opts)))))

(def url-for
  (url-for-routes (routes identity {})))

(defn match-route [path-info]
  (route/try-routing-for (routes identity {}) :map-tree path-info :get))

(comment
  (url-for :artifact/version :path-params {:group-id "a" :artifact-id "b" :version "c"})

  (clojure.pprint/pprint
   (routes identity {}))

  )
