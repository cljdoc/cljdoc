(ns ^:slow cljdoc.integration-test
  (:require [cljdoc.server.system :as sys]
            [cljdoc.util :as util]
            [io.pedestal.test :as pdt]
            [integrant.core :as ig]
            [ring.util.codec :as codec]
            [clojure.java.io :as io]
            [clojure.spec.test.alpha :as st]
            [clojure.test :as t]
            [clojure.string :as string])
  (:import (org.jsoup Jsoup)))

(defonce sys (atom nil))

(defn run-system [tests]
  (st/instrument)
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
    (shutdown-agents)
    (util/delete-directory! (io/file dir))))

(t/use-fixtures :once run-system)

(defn service-fn [sys]
  (assert (get-in sys [:cljdoc/pedestal :io.pedestal.http/service-fn]))
  (get-in sys [:cljdoc/pedestal :io.pedestal.http/service-fn]))

(t/deftest home-page-test
  (t/is (true? (.contains (:body (pdt/response-for (service-fn @sys) :get "/"))
                          "is a website building &amp; hosting documentation for Clojure/Script libraries"))))

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

      (loop [i 20]
        (if (pos? i)
          (when-not (.contains (:body (pdt/response-for (service-fn @sys) :get build-uri))
                               "Successfully imported 10 namespaces")
            (do (Thread/sleep 2000)
                (recur (dec i))))
          (throw (Exception. "Import took too long"))))

      (t/is (true? (.contains (:body (pdt/response-for (service-fn @sys) :get build-uri)) "Git Import Completed")))
      (t/is (true? (.contains (:body (pdt/response-for (service-fn @sys) :get build-uri)) "Successfully imported 10 namespaces")))

      (t/is (= 302 (:status (pdt/response-for (service-fn @sys) :get "/d/reagent/reagent/0.8.1"))))

      (doseq [[p str] {"/d/reagent/reagent/0.8.1/api/reagent.core" "adapt-react-class"
                       "/d/reagent/reagent/0.8.1/doc/tutorials/when-do-components-update-" "In this, more intermediate, Reagent tutorial"}]
        (t/is (.contains (:body (pdt/response-for (service-fn @sys) :get p)) str)))))

  ;; test if atleast one meta tag has the project's description
  (t/is
   (true?
    (-> (pdt/response-for
         (service-fn @sys)
         :get
         "/d/reagent/reagent/0.8.1/doc/documentation-index")
        (:body)
        (Jsoup/parse)
        (.select "head > meta")
        (str)
        (string/includes? "reagent: A simple ClojureScript interface to React Documentation for reagent v0.8.1 on cljdoc.")))))


(comment
  (def s (ig/init (sys/system-config (test-config))))

  (ig/halt! s)

  (t/run-tests)

  (ig/halt! @sys)

  )
