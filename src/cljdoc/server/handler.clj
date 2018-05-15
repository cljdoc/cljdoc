(ns cljdoc.server.handler
  (:require [cljdoc.server.api :as api]
            [cljdoc.server.doc-pages :as dp]
            [clojure.java.io :as io]
            [yada.resources.classpath-resource]))

;; TODO set this up for development
;; (Thread/setDefaultUncaughtExceptionHandler
;;  (reify Thread$UncaughtExceptionHandler
;;    (uncaughtException [_ thread ex]
;;      (log/error ex "Uncaught exception on" (.getName thread)))))

(defn cljdoc-routes [{:keys [dir] :as deps}]
  ["" [["/api" (api/routes deps)]
       (cljdoc.routes/html-routes (partial dp/doc-page (io/file dir "grimoire")))
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
