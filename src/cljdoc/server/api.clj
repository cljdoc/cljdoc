(ns cljdoc.server.api
  (:require [cljdoc.server.yada-jsonista] ; extends yada multimethods
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.util]
            [cljdoc.cache]
            [cljdoc.routes]
            [cljdoc.config] ; should not be necessary but instead be passed as args
            [cljdoc.renderers.html :as html]
            [clojure.tools.logging :as log]
            [cljdoc.grimoire-helpers]
            [cljdoc.git-repo]
            [cljdoc.spec]
            [clojure.java.io :as io]
            [confetti.s3-deploy :as s3]
            [aleph.http :as http]
            [yada.yada :as yada]
            [yada.request-body :as req-body]
            [yada.body :as res-body]
            [byte-streams :as bs]
            [jsonista.core :as json]))


;; Circle CI API stuff -----------------------------------------------
;; TODO move into separate namespace and maybe record later

(defn get-build [circle-ci-config build-num]
  @(http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num)
             {:accept "application/json"
              :basic-auth [(:api-token circle-ci-config) ""]}))

;; (defn get-artifacts [circle-ci-config build-num]
;;   @(http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num "/artifacts?circle-token=:token")
;;              {:accept "application/json"
;;               :basic-auth [(:api-token circle-ci-config) ""]}))

(defn trigger-analysis-build
  [circle-ci-config {:keys [build-id project version jarpath]}]
  {:pre [(string? build-id) (string? project) (string? version) (string? jarpath)]}
  (assert false "deprecated")
  #_(if (:api-token circle-ci-config)
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
  @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/full-build")
              {:form-params params
               :content-type "application/x-www-form-urlencoded"
               :basic-auth ["cljdoc" "cljdoc"]})) ;TODO fix

(defn test-webhook [circle-ci-config build-num]
  (let [payload (-> (get-build circle-ci-config build-num) :body bs/to-string json/read-value)]
    @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/hooks/circle-ci")
                {:body (json/write-value-as-string {"payload" payload})
                 :content-type "application/json"})))

;; Auth --------------------------------------------------------------

(defn mk-auth [known-users]
  (fn authenticate [[user password]]
    (if (get known-users user)
      {:user  user
       :roles #{:api-user}}
      {})))

(defn api-acc-control [users]
  {:realm "accounts"
   :scheme "Basic"
   :verify (mk-auth users)
   :authorization {:methods {:post :api-user}}})

(defn circle-ci-webhook-handler [circle-ci]
  ;; TODO assert config correctness
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
                         artifacts  (-> (analysis-service/get-circle-ci-build-artifacts circle-ci build-num)
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

(defn initiate-build-handler-simple [{:keys [analysis-service]}]
  (yada/handler
   (yada/resource
    {:methods
     {:post
      ;; TODO don't take jarpath as an argument here but instead derive it
      ;; from project and version information. If we take jarpath as an argument
      ;; we lose the immutability guarantees that we get when only talking to Clojars
      {:parameters {:form {:project String :version String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :produces "text/html"
       :response (fn initiate-build-handler-simple-response [ctx]
                   (let [project   (get-in ctx [:parameters :form :project])
                         version   (get-in ctx [:parameters :form :version])
                         jarpath   (cljdoc.util/remote-jar-file [project version])
                         build-id  (str (java.util.UUID/randomUUID))
                         ana-resp  (analysis-service/trigger-build
                                    analysis-service
                                    {:project project
                                     :version version
                                     :jarpath jarpath
                                     :build-id build-id})]
                     (if (analysis-service/circle-ci? analysis-service)
                       (let [build-num (-> ana-resp :body bs/to-string json/read-value (get "build_num"))
                             job-url   (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)]
                         (telegram/build-requested project version job-url)
                         (when (= 201 (:status ana-resp))
                           (assert build-num "build number missing from CircleCI response")
                           (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}"
                                      build-id job-url))
                         (str (html/build-submitted-page job-url)))
                       (str (html/local-build-submitted-page)))))}}})))

(defn initiate-build-handler [{:keys [access-control analysis-service]}]
  ;; TODO assert config correctness
  (yada/handler
   (yada/resource
    {:access-control access-control
     :methods
     {:post
      ;; TODO don't take jarpath as an argument here but instead derive it
      ;; from project and version information. If we take jarpath as an argument
      ;; we lose the immutability guarantees that we get when only talking to Clojars
      {:parameters {:form {:project String :version String :jarpath String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :response (fn initiate-build-handler-response [ctx]
                   (let [build-id  (str (java.util.UUID/randomUUID))
                         ci-resp   (analysis-service/trigger-build
                                    analysis-service
                                    (-> (get-in ctx [:parameters :form])
                                        (assoc :build-id build-id)))
                         build-num (-> ci-resp :body bs/to-string json/read-value (get "build_num"))]
                     (when (= 201 (:status ci-resp))
                       (assert build-num "build number missing from CircleCI response")
                       (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}"
                                  build-id
                                  (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)))
                     (assoc (:response ctx) :status (:status ci-resp))))}}})))

(defn full-build-handler [{:keys [dir s3-deploy access-control]}]
  ;; TODO assert config correctness
  (yada/handler
   (yada/resource
    {:access-control access-control
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

                       (log/info "Verifying cljdoc-edn contents against spec")
                       (cljdoc.spec/assert :cljdoc/cljdoc-edn cljdoc-edn)

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

                         (telegram/import-completed
                          (cljdoc.routes/path-for
                           :artifact/index
                           {:group-id (cljdoc.util/group-id project)
                            :artifact-id (cljdoc.util/artifact-id project)
                            :version version}))

                         ;; (log/info "Rendering HTML")
                         ;; (log/info "html-dir" html-dir)
                         ;; (cljdoc.cache/render
                         ;;  (cljdoc.renderers.html/->HTMLRenderer)
                         ;;  (cljdoc.cache/bundle-docs
                         ;;   (cljdoc.grimoire-helpers/grimoire-store grimoire-dir)
                         ;;   (cljdoc.grimoire-helpers/version-thing project version))
                         ;;  {:dir html-dir})

                         ;; (log/info "Deploying")

                         ;; (s3/sync! (select-keys s3-deploy [:access-key :secret-key])
                         ;;           (:bucket s3-deploy)
                         ;;           (s3/dir->file-maps html-dir)
                         ;;           {:report-fn #(log/info %)})

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

;; Routes -------------------------------------------------------------

(defn routes [{:keys [analysis-service dir s3-deploy]}]
  [["/ping"            ping-handler]
   ["/hooks/circle-ci" (circle-ci-webhook-handler analysis-service)]
   ["/request-build"   (initiate-build-handler
                        {:analysis-service analysis-service
                         :access-control (api-acc-control {"cljdoc" "cljdoc"})})]
   ["/request-build2"  (initiate-build-handler-simple
                        {:analysis-service analysis-service})]
   ["/full-build"      (full-build-handler
                        {:dir dir
                         :s3-deploy s3-deploy
                         :access-control (api-acc-control {"cljdoc" "cljdoc"})})]])
