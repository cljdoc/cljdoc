(ns cljdoc.integration-test
  (:require [cljdoc.server.system :as sys]
            [cljdoc.util :as util]
            [io.pedestal.test :as pdt]
            [integrant.core :as ig]
            [ring.util.codec :as codec]
            [clojure.java.io :as io]
            [clojure.test :as t]))

(defonce sys (atom nil))

(defn run-system [tests]
  (let [dir "test-data/"
        cfg {:cljdoc/server {:port (+ 8000 (rand-int 1000))
                             :analysis-service :local
                             :autobuild-clojars-releases? false
                             :dir dir}}]
    (assert (not (.exists (io/file dir)))
            (format "test data directory exists, please clear before running tests: %s" dir))
    (reset! sys (ig/init (sys/system-config cfg)))
    (tests)
    (ig/halt! @sys)
    (util/delete-directory! (io/file dir))))

(t/use-fixtures :once run-system)

(defn service-fn [sys]
  (assert (get-in sys [:cljdoc/pedestal :io.pedestal.http/service-fn]))
  (get-in sys [:cljdoc/pedestal :io.pedestal.http/service-fn]))

(t/deftest home-page-test
  (t/is (true? (.contains (:body (pdt/response-for (service-fn @sys) :get "/"))
                          "is a platform to build, host and view"))))

(t/deftest full-cycle-test
  ;; (t/is (true? (.contains (:body (pdt/response-for (service-fn @sys) :get "/d/reagent/reagent/0.8.1"))
  ;;                         "We currently don't have documentation built for reagent v0.8.1")))

  (let [params    (codec/form-encode {:project "reagent" :version "0.8.1"})
        build-req (pdt/response-for (service-fn @sys)
                                    :post "/api/request-build2"
                                    :body params
                                    :headers {"Content-Type" "application/x-www-form-urlencoded"})]
    (t/is (= 303 (:status build-req)))
    (t/is (= "/builds/1" (get-in build-req [:headers "Location"])))

    (let [build-uri   (get-in build-req [:headers "Location"])
          builds-page (pdt/response-for (service-fn @sys) :get build-uri)]

      (t/is (true? (.contains (:body builds-page) "reagent/reagent")))
      (t/is (true? (.contains (:body builds-page) "v0.8.1")))
      (t/is (true? (.contains (:body builds-page) "Analysis Requested")))

      (loop [i 10]
        (when (and (pos? i)
                   (not (.contains (:body (pdt/response-for (service-fn @sys) :get build-uri))
                                   "Import Completed")))
          (Thread/sleep 2000)
          (recur (dec i))))

      (t/is (true? (.contains (:body (pdt/response-for (service-fn @sys) :get build-uri))
                              "Import Completed")))

      (t/is (= 302 (:status (pdt/response-for (service-fn @sys) :get "/d/reagent/reagent/0.8.1"))))

      (doseq [[p str] {"/d/reagent/reagent/0.8.1/api/reagent.core" "adapt-react-class"
                       "/d/reagent/reagent/0.8.1/doc/tutorials/when-do-components-update-" "In this, more intermediate, Reagent tutorial"}]
        (t/is (.contains (:body (pdt/response-for (service-fn @sys) :get p)) str))))))

(comment
  (def s (ig/init (sys/system-config (test-config))))

  (ig/halt! s)

  (t/run-tests)

  (ig/halt! @sys)

  )
