(ns cljdoc.render.api-searchset-test
  (:require [cljdoc.render.api-searchset :as api-searchset]
            [cljdoc.spec.cache-bundle :as cbs]
            [cljdoc.spec.searchset :as ss]
            [clojure.edn :as edn]
            [clojure.test :as t]
            [clojure.java.io :as io]))

(def cache-bundle (-> "test_data/cache_bundle.edn"
                      io/resource
                      slurp
                      edn/read-string))

(comment
  (require '[clojure.pprint]
           '[cljdoc.spec.util :as util])
  (let [cache-bundle (util/load-cache-bundle "seancorfield/next.jdbc/1.2.659")
        searchset (api-searchset/cache-bundle->searchset cache-bundle)]
    (spit "resources/test_data/cache_bundle.edn" (with-out-str (clojure.pprint/pprint cache-bundle)))
    (spit "resources/test_data/searchset.edn" (with-out-str (clojure.pprint/pprint searchset)))
    ;; make sure you check these to confirm that namespaces + defs + docs are all generating correctly
    ))

(def doc (get-in cache-bundle [:version :doc 0]))

(def version-entity (:version-entity cache-bundle))

(def searchset (-> "test_data/searchset.edn"
                   io/resource
                   slurp
                   edn/read-string))

(t/deftest path-for-doc
  (t/testing "gets the route for a given doc"
    (t/is (= "/d/seancorfield/next.jdbc/1.2.659/doc/readme"
             (api-searchset/path-for-doc doc version-entity))))
  (t/testing "gets the route if the doc has a slug path instead of a slug"
    (let [attrs (:attrs doc)
          slug-path-attrs (-> attrs (dissoc :slug) (assoc :slug-path ["read" "me"]))
          slug-path-doc (assoc doc :attrs slug-path-attrs)]
      (t/is (= "/d/seancorfield/next.jdbc/1.2.659/doc/read/me"
               (api-searchset/path-for-doc slug-path-doc version-entity))))))

(t/deftest path-for-namespace
  (t/testing "gets a route for a namespace"
    (t/is (= "/d/seancorfield/next.jdbc/1.2.659/api/next.jdbc.connection"
             (api-searchset/path-for-namespace version-entity "next.jdbc.connection")))))

(t/deftest path-for-def
  (t/testing "gets a route for a def"
    (t/is (= "/d/seancorfield/next.jdbc/1.2.659/api/next.jdbc#prepare"
             (api-searchset/path-for-def version-entity "next.jdbc" "prepare")))))

(t/deftest cache-bundle->searchset
  (let [generated-searchset (api-searchset/cache-bundle->searchset cache-bundle)]
    (t/testing "input cache bundle is valid"
      (let [explanation (cbs/explain-humanized cache-bundle)]
        (t/is (nil? explanation) {:explanation explanation
                                  :data cache-bundle})))
    (t/testing "converts a cache-bundle into a searchset"
      (t/is (= searchset generated-searchset)))
    (t/testing "produces a valid searchset"
      (let [explanation (ss/explain-humanized generated-searchset)]
        (t/is (nil? explanation) {:explanation explanation
                                  :data generated-searchset})))))
