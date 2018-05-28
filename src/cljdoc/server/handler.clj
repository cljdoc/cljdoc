(ns cljdoc.server.handler
  (:require [cljdoc.server.api :as api]
            [cljdoc.server.doc-pages :as dp]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.renderers.build-log]
            [clojure.java.io :as io]
            [yada.resources.classpath-resource]))

;; TODO set this up for development
;; (Thread/setDefaultUncaughtExceptionHandler
;;  (reify Thread$UncaughtExceptionHandler
;;    (uncaughtException [_ thread ex]
;;      (log/error ex "Uncaught exception on" (.getName thread)))))

(defn html-resource [response-fn]
  (yada.yada/handler
   (yada.yada/resource
    {:methods {:get {:produces #{"text/html"}
                     :response response-fn}}})))

(defn home [ctx]
  (cljdoc.renderers.html/home))

(defn build-page [build-tracker]
  (fn build-page-response [ctx]
    (->> (:id (:route-params ctx))
         (build-log/get-build build-tracker)
         (cljdoc.renderers.build-log/build-page)
         str)))

(defn builds-page [build-tracker]
  (fn build-page-response [ctx]
    (->> (build-log/recent-builds build-tracker 100)
         (cljdoc.renderers.build-log/builds-page)
         str)))

(defn cljdoc-routes [{:keys [dir build-tracker] :as deps}]
  ["" [["/api" (api/routes deps)]
       (cljdoc.routes/html-routes (partial dp/doc-page (io/file dir "grimoire")))
       [["/builds/" :id] (html-resource (build-page build-tracker))]
       ["/builds" (html-resource (builds-page build-tracker))]
       ["/" (html-resource home)]
       ["" (yada.resources.classpath-resource/new-classpath-resource "public")]]])

(comment
  (def c (cljdoc.config/circle-ci))

  (def r
    (trigger-analysis-build
     (cljdoc.config/circle-ci)
     {:project "bidi" :version "2.1.3" :jarpath "https://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.jar"}))

  (def b
    (-> r :body bs/to-string))

  (get (json/read-value b) "build_num")

  (def build (get-build c 25))

  (defn pp-body [r]
    (-> r :body bs/to-string json/read-value clojure.pprint/pprint))

  (get parsed-build "build_parameters")

  (test-webhook c 27)

  (pp-body (get-artifacts c 24))

  (-> (get-artifacts c 24)
      :body bs/to-string json/read-value)

  (run-full-build {:project "bidi"
                   :version "2.1.3"
                   :build-id "something"
                   :cljdoc-edn "https://27-119377591-gh.circle-artifacts.com/0/cljdoc-edn/bidi/bidi/2.1.3/cljdoc.edn"})

  (log/error (ex-info "some stuff" {}) "test")

  )
