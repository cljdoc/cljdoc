(ns cljdoc.server.handler
  (:require [cljdoc.server.yada-jsonista] ; extends yada multimethods
            [cljdoc.util]
            [cljdoc.cache]
            [cljdoc.renderers.html]
            [clojure.tools.logging :as log]
            [cljdoc.grimoire-helpers]
            [cljdoc.git-repo]
            [clojure.java.io :as io]
            [confetti.s3-deploy :as s3]
            [aleph.http :as http]
            [yada.yada :as yada]
            [yada.request-body :as req-body]
            [yada.body :as res-body]
            [byte-streams :as bs]
            [jsonista.core :as json]))

;; TODO set this up for development
;; (Thread/setDefaultUncaughtExceptionHandler
;;  (reify Thread$UncaughtExceptionHandler
;;    (uncaughtException [_ thread ex]
;;      (log/error ex "Uncaught exception on" (.getName thread)))))

;; Circle CI API stuff -----------------------------------------------
;; TODO move into separate namespace and maybe record later

(defn get-build [circle-ci-config build-num]
  @(http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num)
             {:accept "application/json"
              :basic-auth [(:api-token circle-ci-config) ""]}))

(defn get-artifacts [circle-ci-config build-num]
  @(http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num "/artifacts?circle-token=:token")
             {:accept "application/json"
              :basic-auth [(:api-token circle-ci-config) ""]}))

(defn trigger-analysis-build
  [circle-ci-config {:keys [build-id project version jarpath]}]
  {:pre [(string? build-id) (string? project) (string? version) (string? jarpath)]}
  (if (:api-token circle-ci-config)
    @(http/post (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/tree/master")
                {:accept "application/json"
                 :form-params {"build_parameters" {"CLJDOC_BUILD_ID" build-id
                                                   "CLJDOC_PROJECT" project
                                                   "CLJDOC_PROJECT_VERSION" version
                                                   "CLJDOC_PROJECT_JAR" jarpath}}
                 :basic-auth [(:api-token circle-ci-config) ""]})
    (log/warn "No API token present to communicate with CircleCI, skipping request")))


;; cljdoc API client functions ---------------------------------------

(defn run-full-build [params]
  @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/full-build")
              {:form-params params
               :content-type "application/x-www-form-urlencoded"
               :basic-auth ["cljdoc" "cljdoc"]})) ;TODO fix

(defn test-webhook [circle-ci-config build-num]
  (let [payload (-> (get-build circle-ci-config build-num) :body bs/to-string json/read-value)]
    @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/hooks/circle-ci")
                {:body (json/write-value-as-string {"payload" payload})
                 :content-type "application/json"})))

;; Auth --------------------------------------------------------------

(defn auth [[user password]]
  (let [m {"cljdoc" "cljdoc"}]
    (if (get m user)
      {:user  user
       :roles #{:api-user}}
      {})))

(def cljdoc-admins
  {:realm "accounts"
   :scheme "Basic"
   :verify auth
   :authorization {:methods {:post :api-user}}})

(defn circle-ci-webhook-handler [circle-ci-config]
  (yada/handler
   (yada/resource
    {:methods
     {:post
      {:consumes #{"application/json"}
       :produces "text/plain"
       :response (fn webhook-req-handler [ctx]
                   ;; TODO implement some rate limiting based on build-number
                   ;; this should be sufficient for now since triggering new
                   ;; builds requires authentication
                   (let [build-num (get-in ctx [:body "payload" "build_num"])
                         project   (get-in ctx [:body "payload" "build_parameters" "CLJDOC_PROJECT"])
                         version   (get-in ctx [:body "payload" "build_parameters" "CLJDOC_PROJECT_VERSION"])
                         build-id  (get-in ctx [:body "payload" "build_parameters" "CLJDOC_BUILD_ID"])
                         cljdoc-edn (cljdoc.util/cljdoc-edn project version)
                         artifacts  (-> (get-artifacts circle-ci-config build-num)
                                        :body bs/to-string json/read-value)]
                     (if-let [artifact (and (= 1 (count artifacts))
                                            (= cljdoc-edn (get (first artifacts) "path"))
                                            (first artifacts))]
                       (do
                         (log/info "Found expected artifact:" (first artifacts))
                         (let [full-build-req (run-full-build {:project project
                                                               :version version
                                                               :build-id build-id
                                                               :cljdoc-edn (get artifact "url")})]
                           (assoc (:response ctx) :status (:status full-build-req))))
                       (do
                         (log/warn "Unexpected artifacts for build submitted via webhook" artifacts)
                         (log/warn "Expected path" cljdoc-edn)
                         (assoc (:response ctx) :status 400)))))}}})))

(defn initiate-build-handler [circle-ci-config]
  (yada/handler
   (yada/resource
    {:access-control cljdoc-admins
     :methods
     {:post
      ;; TODO don't take jarpath as an argument here but instead derive it
      ;; from project and version information. If we take jarpath as an argument
      ;; we lose the immutability guarantees that we get when only talking to Clojars
      {:parameters {:form {:project String :version String :jarpath String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :response (fn initiate-build-handler-response [ctx]
                   (let [build-id  (str (java.util.UUID/randomUUID))
                         ci-resp   (trigger-analysis-build
                                    circle-ci-config
                                    (-> (get-in ctx [:parameters :form])
                                        (assoc :build-id build-id)))
                         build-num (-> ci-resp :body bs/to-string json/read-value (get "build_num"))]
                     (when (= 201 (:status ci-resp))
                       (assert build-num "build number missing from CircleCI response")
                       (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}"
                                  build-id
                                  (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)))
                     (assoc (:response ctx) :status (:status ci-resp))))}}})))

(defn full-build-handler [{:keys [dir deploy-bucket]}]
  (yada/handler
   (yada/resource
    {:access-control cljdoc-admins
     :methods
     {:post
      {:parameters {:form {:project String :version String  :build-id String :cljdoc-edn String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :response (fn full-build-handler-response [ctx]
                   (try
                     (let [{:keys [project version build-id cljdoc-edn]} (get-in ctx [:parameters :form])
                           cljdoc-edn   (clojure.edn/read-string (slurp cljdoc-edn))
                           git-dir      (io/file dir (cljdoc.util/git-dir project version))
                           grimoire-dir (doto (io/file dir "grimoire") (.mkdir))
                           html-dir     (doto (io/file dir "grimoire-html") (.mkdir))
                           scm-url      (cljdoc.util/scm-url (:pom cljdoc-edn))]
                       (log/info "Cloning Git repo" scm-url)
                       (when-not (.exists git-dir) ; TODO we should really wipe and start fresh for each build
                         (cljdoc.git-repo/clone scm-url git-dir))
                       (let [repo        (cljdoc.git-repo/->repo git-dir)
                             version-tag (->> (cljdoc.git-repo/git-tag-names repo)
                                              (filter #(cljdoc.util/version-tag? version %))
                                              first)]
                         (if version-tag
                           (cljdoc.git-repo/git-checkout-repo repo version-tag)
                           (log/warn "No version tag found for version %s in %s\n" version scm-url))

                         (log/info "Importing into Grimoire")
                         (cljdoc.grimoire-helpers/import
                          {:cljdoc-edn   cljdoc-edn
                           :grimoire-dir grimoire-dir
                           :git-repo     repo})

                         (log/info "Rendering HTML")
                         (log/info "html-dir" html-dir)
                         (cljdoc.cache/render
                          (cljdoc.renderers.html/->HTMLRenderer)
                          (cljdoc.cache/bundle-docs
                           (cljdoc.grimoire-helpers/grimoire-store grimoire-dir)
                           (cljdoc.grimoire-helpers/version-thing project version))
                          {:dir html-dir})

                         (log/info "Deploying")

                         (s3/sync! (select-keys deploy-bucket [:access-key :secret-key])
                                   (:s3-bucket-name deploy-bucket)
                                   (s3/dir->file-maps html-dir)
                                   {:report-fn #(log/info %1 %2)})

                         (log/infof "Done with build %s" build-id))

                       (assoc (:response ctx) :status 200))
                     (catch Throwable t
                       (log/error t "Exception while running full build"))))}}})))

(def ping-handler
  (yada/handler
   (yada/resource
    {:methods
     {:get {:produces "text/plain"
            :response "pong"}}})))

(defn cljdoc-api-routes [{:keys [circle-ci dir s3-deploy] :as deps}]
  ["" [["/ping"            ping-handler]
       ["/hooks/circle-ci" (circle-ci-webhook-handler circle-ci)]
       ["/request-build"   (initiate-build-handler circle-ci)]
       ["/full-build"      (full-build-handler (select-keys deps [:dir :deploy-bucket]))]
       ]])

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
