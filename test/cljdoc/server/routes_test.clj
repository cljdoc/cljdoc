(ns cljdoc.server.routes-test
  (:require [cljdoc.server.routes :as routes]
            [clojure.test :as t]
            [io.pedestal.http.route :refer [expand-routes try-routing-for]]))

(t/deftest badge-for-project-test
  (let [table (-> (routes/utility-routes)
                  expand-routes)
        dispatch (fn [path]
                   (try-routing-for table :map-tree path :get))]
    (t/is (some? (dispatch "/badge/foo")))
    (t/is (some? (dispatch "/badge/foo/bar")))
    (t/is (nil?  (dispatch "/badge/foo/bar/baz")))))

(t/deftest url-for-test
  (t/is (= "/api/ping" (routes/url-for :ping)) "route without params")
  (t/is (= "/d/foo/bar/baz/doc/quux" (routes/url-for :artifact/doc {:params {:group-id "foo" :artifact-id "bar" :version "baz" :article-slug "quux"}}))
        "route with params")
  (t/is (thrown? Exception (routes/url-for :artifact/doc {:params {:article-slug "quux"}}))
        "route with missing"))
