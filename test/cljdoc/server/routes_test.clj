(ns cljdoc.server.routes-test
  (:require [io.pedestal.http.route :refer [expand-routes try-routing-for]]
            [cljdoc.server.routes :as routes]
            [clojure.test :as t]))

(t/deftest badge-for-project-test
  (let [table (-> (routes/utility-routes)
                  expand-routes)
        dispatch (fn [path]
                   (try-routing-for table :map-tree path :get))]
    (t/is (some? (dispatch "/badge/foo")))
    (t/is (some? (dispatch "/badge/foo/bar")))
    (t/is (nil?  (dispatch "/badge/foo/bar/baz")))))
