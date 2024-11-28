(ns cljdoc.deploy
  "This namespace schedules the cljdoc Clojure service and the Traefik
  load balancer via Nomad.

  It communicates with Nomad and Consul by establishing an SSH port forwarding
  so the Nomad and Consul ports can be accessed on the local machine.

  The deploy is happening in two stages:

  1. Create a new Nomad deployment with the new version running as a canary.
  2. Promote the Nomad deployment, resulting in a shut down of previous deployments."
  (:require [aero.core :as aero]
            [cheshire.core :as json]
            [cli-matic.core :as cli-matic]
            [clj-http.lite.client :as http]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [unilog.config :as unilog])
  (:import (com.jcraft.jsch JSch)
           (java.util.concurrent TimeUnit)))

(unilog/start-logging! {:level :info :console true})

(def ^:private nomad-base-uri "http://localhost:4646")
(def ^:private consul-base-uri "http://localhost:8500")

(defn- nomad-get [path]
  (json/parse-string (:body (http/get (str nomad-base-uri path)))))

(defn- nomad-post! [path body]
  (->> (http/post (str nomad-base-uri path)
                  {:body (json/generate-string body)})
       :body
       json/parse-string))

(defn- consul-put! [k v]
  (->> (http/put (str consul-base-uri "/v1/kv/" k)
                 {:body v})
       :body
       json/parse-string))

(defmethod aero/reader 'nomad/seconds
  [_ _tag value]
  (* value 1000000000))

(defmethod aero/reader 'env!
  [_ _tag envvar]
  (if-some [v (System/getenv (str envvar))]
    v
    (throw (Exception. (format "Could not find env var for %s" envvar)))))

(defmethod aero/reader `opt
  [{::keys [opts]} _tag value]
  (or (get opts value)
      (throw (ex-info (str "Could not find deploy opt for " value)
                      {:opts opts}))))

(defn- wait-until [desc pred test-interval-ms max-time-secs]
  (let [deadline (+ (System/currentTimeMillis) (* max-time-secs 1000))]
    (log/infof "%s: will retry every %dms for max of %dm%ds"
               desc
               test-interval-ms
               (int (/ max-time-secs 60))
               (mod max-time-secs 60))
    (loop [try-num 1]
      (if-let [res (pred)]
        (do
          (log/infof "%s: success on try %d" desc try-num)
          res)
        (do
          (when (> (System/currentTimeMillis) deadline)
            (throw (Exception. (format "%s: timed out after failed try %d" desc try-num))))
          (log/infof "%s: failed on try %d" desc try-num)
          (Thread/sleep test-interval-ms)
          (recur (inc try-num)))))))

(defn- promote-deployment! [deployment-id]
  (nomad-post! (str "/v1/deployment/promote/" deployment-id)
               {"DeploymentID" deployment-id
                "All" true}))

(defn- deployment-healthy? [deployment-id]
  (let [deployment     (nomad-get (str "/v1/deployment/" deployment-id))
        desired-total  (get-in deployment ["TaskGroups" "cljdoc" "DesiredTotal"])
        healthy-allocs (get-in deployment ["TaskGroups" "cljdoc" "HealthyAllocs"])
        placed-allocs  (get-in deployment ["TaskGroups" "cljdoc" "PlacedAllocs"])
        status         (get-in deployment ["Status"])]
    (log/infof "%d placed / %d healthy / %d desired  - status: '%s'" placed-allocs healthy-allocs desired-total status)
    (assert (= placed-allocs desired-total) "Not enough allocs placed")
    (assert (not= "failed" status) "Deployment failed")
    (= desired-total healthy-allocs)))

(defn- tag-exists?
  "Return true if given `tag` exists in the DockerHub cljdoc/cljdoc repository."
  [tag]
  (let [status (:status (http/head (format "https://hub.docker.com/v2/repositories/cljdoc/cljdoc/tags/%s/" tag)
                                   {:throw-exceptions false}))]
    (log/info "check for existence of docker tag" tag "returned" status)
    (= 200 status)))

(defn- jobspec [opts]
  (aero/read-config (io/resource "cljdoc.jobspec.edn")
                    {::opts opts}))

(defn- secrets-config [{:keys [secrets-filename]}]
  (with-out-str
    (pp/pprint
     (aero/read-config
      (io/file secrets-filename)))))

(defn- traefik-config [{:keys [cljdoc-profile]}]
  (-> (io/resource "traefik.edn")
      (aero/read-config
       {::opts {:lets-encrypt-endpoint "https://acme-staging-v02.api.letsencrypt.org/directory"
                #_(if (= "prod" cljdoc-profile)
                  "https://acme-v02.api.letsencrypt.org/directory"
                  "https://acme-staging-v02.api.letsencrypt.org/directory")}})
      (yaml/generate-string :dumper-options {:indent 2
                                             :flow-style :block})))

(defn- sync-config! [opts]
  (doseq [[k v] {"config/traefik-yaml" (traefik-config opts)
                 "config/cljdoc/secrets-edn" (secrets-config opts)}]
    (log/info "Syncing configuration:" k)
    (consul-put! k v)))

(defn- run-nomad-job! [job-spec]
  (let [run-result (nomad-post! "/v1/jobs" job-spec)
        warnings (get run-result "Warnings")]
    (when warnings
      (log/warnf "Create job returned warnings:\n%s" warnings))
    run-result))

(defn- deploy!
  "Deploy the specified docker tag to the Nomad instance listening on
  localhost:4646.

  This assumes that either the port has been forwarded from a remote
  host or that Nomad is running on localhost."
  [opts]
  (sync-config! opts)
  (let [run-result (run-nomad-job! (jobspec opts))
        eval-id (get run-result "EvalID")
        eval (wait-until "evaluation is complete"
                         (fn get-eval []
                           (let [eval (nomad-get (str "/v1/evaluation/" eval-id))
                                 status (get eval "Status")]
                             (when (= "complete" status) eval)))
                         250
                         (.toSeconds TimeUnit/MINUTES 1))
        deployment-id (get eval "DeploymentID")]
    (assert deployment-id "Deployment ID missing")
    (log/info "Evaluation ID:" eval-id)
    (log/info "Deployment ID:" deployment-id)
    (wait-until "deployment healthy" #(deployment-healthy? deployment-id)
                5000 (.toSeconds TimeUnit/MINUTES 5))
    (let [deployment (nomad-get (str "/v1/deployment/" deployment-id))]
      (if (= "running" (get deployment "Status"))
        (do
          (log/info "Promoting:" deployment-id)
          (promote-deployment! deployment-id))
        (log/info "Nothing to do for deployment status" (pr-str (get deployment "Status")))))))

(defmacro with-nomad [opts & body]
  `(let [nomad-ip# (:nomad-ip ~opts)
         ssh-key# (:ssh-key ~opts)
         ssh-user# (:ssh-user ~opts)
         jsch#    (JSch.)
         session# (.getSession jsch# ssh-user# nomad-ip#)]
     (.addIdentity jsch# ssh-key#)
     (JSch/setConfig "StrictHostKeyChecking" "no")
     (.connect session# 5000)
     (try
       (.setPortForwardingL session# 8500 "localhost" 8500)
       (.setPortForwardingL session# 4646 "localhost" 4646)
       ~@body
       (finally
         (.disconnect session#)))))

(defn- cli-deploy! [{:keys [nomad-ip] :as connect-opts}
                    {:keys [docker-tag cljdoc-profile] :as deploy-opts}]
  (wait-until (format "docker tag %s exists" docker-tag) #(tag-exists? docker-tag)
              2000 (.toSeconds TimeUnit/MINUTES 3))
  (log/infof "Deploying to Nomad server at %s:4646 using %s cljdoc-profile" nomad-ip cljdoc-profile)
  (with-nomad connect-opts
    (deploy! deploy-opts)))

(def ^:private CONFIGURATION
  {:app         {:command     "cljdoc-deploy"
                 :description "command-line utilities to deploy cljdoc"
                 :version     "0.0.1"}
   :commands    [{:command     "deploy"
                  :description ["Deploy cljdoc to production"]
                  :opts        [{:option "ssh-key" :short "k" :as "SSH private key to use for accessing host" :type :string :default "~/.ssh/id_rsa"}
                                {:option "ssh-user" :short "u" :as "SSH user" :type :string :default :present}
                                {:option "nomad-ip" :short "i" :as "IP of Nomad cluster to deploy to" :type :string}
                                {:option "docker-tag" :short "t" :as "Tag of cljdoc/cljdoc image to deploy" :type :string :default :present}
                                {:option "cljdoc-profile" :short "p" :as "Cljdoc profile" :type :string :default "prod"}
                                {:option "secrets-filename" :short "s" :as "Secrets edn file" :type :string :default "resources/secrets.edn"}]
                  :runs        (fn [opts] (cli-deploy!
                                           (select-keys opts [:ssh-key :ssh-user :nomad-ip])
                                           (select-keys opts [:docker-tag :cljdoc-profile :secrets-filename])))}]})

(defn -main
  [& args]
  (cli-matic/run-cmd args CONFIGURATION))

(comment
  (aero/read-config "/home/lee/proj/oss/cljdoc/cljdoc/resources/config.edn" {:profile :default})

  ;; local testing against debian in a VirtualBox VM
  (def deploy-opts {:docker-tag "0.0.2732-lread-explore-mem-usage-6f7fa85"
                    :cljdoc-profile "default"
                    :secrets-filename "../../../resources/secrets.edn"})

  (defmacro local-test [& body]
    `(with-nomad {:nomad-ip "10.0.1.20"
                  :ssh-key "/home/lee/.ssh/id_ed25519_cljdoc_local_vm_testing"
                  :ssh-user "root"}
       ~@body))

  (cli-deploy! {:nomad-ip "10.0.1.20"
                :ssh-key "/home/lee/.ssh/id_ed25519_cljdoc_local_vm_testing"
                :ssh-user "root"}
               {:docker-tag "0.0.2732-lread-explore-mem-usage-6f7fa85"
                :cljdoc-profile "default"
                :secrets-filename "../../../resources/secrets.edn"})

  (local-test
   (nomad-post! "/v1/validate/job" (jobspec deploy-opts)))
  ;; => {"DriverConfigValidated" true,
  ;;     "ValidationErrors" nil,
  ;;     "Error" "",
  ;;     "Warnings" ""}

  (jobspec deploy-opts)
  ;; => {:Job
  ;;     {:Datacenters ["dc1"],
  ;;      :ID "cljdoc",
  ;;      :Name "cljdoc",
  ;;      :TaskGroups
  ;;      [{:Count 1,
  ;;        :Name "cljdoc",
  ;;        :RestartPolicy
  ;;        {:Attempts 2, :Delay 15000000000, :Interval 1800000000000, :Mode "fail"},
  ;;        :Networks
  ;;        [{:DynamicPorts [{:Label "http", :To 8000} {:Label "jmx", :To 9010}]}],
  ;;        :Tasks
  ;;        [{:Env
  ;;          {:CLJDOC_SECRETS "/etc/cljdoc/secrets.edn", :CLJDOC_PROFILE "default"},
  ;;          :Resources {:CPU 1000, :MemoryMB 1600},
  ;;          :KillSignal "",
  ;;          :Config
  ;;          {:image "cljdoc/cljdoc:0.0.2705-91bda72e",
  ;;           :ports ["http" "jmx"],
  ;;           :volumes ["secrets:/etc/cljdoc" "/data/cljdoc:/var/cljdoc"]},
  ;;          :Templates
  ;;          [{:DestPath "secrets/secrets.edn",
  ;;            :EmbeddedTmpl "{{key \"config/cljdoc/secrets-edn\"}}"}],
  ;;          :Driver "docker",
  ;;          :Services
  ;;          [{:Name "cljdoc",
  ;;            :PortLabel "http",
  ;;            :Checks
  ;;            [{:Name "alive",
  ;;              :PortLabel "http",
  ;;              :Interval 10000000000,
  ;;              :Timeout 2000000000,
  ;;              :Type "tcp"}],
  ;;            :Tags
  ;;            ["traefik.enable=true"
  ;;             "traefik.http.middlewares.redirect-to-https.redirectscheme.scheme=https"
  ;;             "traefik.http.middlewares.redirect-to-https.redirectscheme.permanent=true"
  ;;             "traefik.http.routers.cljdoc-http.entrypoints=web"
  ;;             "traefik.http.routers.cljdoc-http.middlewares=redirect-to-https"
  ;;             "traefik.http.routers.cljdoc-http.rule=PathPrefix(`/`)"
  ;;             "traefik.http.middlewares.compress-response.compress=true"
  ;;             "traefik.http.routers.cljdoc-https.entrypoints=websecure"
  ;;             "traefik.http.routers.cljdoc-https.tls=true"
  ;;             "traefik.http.routers.cljdoc-https.tls.certresolver=lets-encrypt"
  ;;             "traefik.http.routers.cljdoc-https.rule=PathPrefix(`/`)"
  ;;             "traefik.http.routers.cljdoc-https.tls.domains[0].main=cljdoc.org"
  ;;             "traefik.http.routers.cljdoc-https.tls.domains[1].main=cljdoc.xyz"
  ;;             "traefik.http.middlewares.xyz-redirect.redirectregex.permanent=true"
  ;;             "traefik.http.middlewares.xyz-redirect.redirectregex.regex=^https://.*cljdoc\\.xyz(.*)"
  ;;             "traefik.http.middlewares.xyz-redirect.redirectregex.replacement=https://cljdoc.org${1}"
  ;;             "traefik.http.routers.cljdoc-https.middlewares=xyz-redirect,compress-response"]}],
  ;;          :Name "backend",
  ;;          :Artifacts nil}],
  ;;        :Update
  ;;        {:Canary 1,
  ;;         :MaxParallel 1,
  ;;         :HealthyDeadline 240000000000,
  ;;         :MinHealthyTime 10000000000}}
  ;;       {:Name "lb",
  ;;        :Networks
  ;;        [{:ReservedPorts
  ;;          [{:Label "api", :Value 8080}
  ;;           {:Label "http", :Value 80}
  ;;           {:Label "https", :Value 443}]}],
  ;;        :Tasks
  ;;        [{:Config
  ;;          {:image "traefik:3.1.5",
  ;;           :network_mode "host",
  ;;           :ports ["api" "http" "https"],
  ;;           :volumes ["local:/etc/traefik" "/data/traefik:/data"]},
  ;;          :Driver "docker",
  ;;          :Name "traefik",
  ;;          :Resources {:CPU 200, :MemoryMB 256},
  ;;          :Services
  ;;          [{:Name "traefik",
  ;;            :PortLabel "http",
  ;;            :Checks
  ;;            [{:Name "alive",
  ;;              :Interval 10000000000,
  ;;              :Timeout 2000000000,
  ;;              :Type "tcp"}]}],
  ;;          :Templates
  ;;          [{:DestPath "local/traefik.yaml",
  ;;            :EmbeddedTmpl "{{key \"config/traefik-yaml\"}}"}]}]}],
  ;;      :Type "service"}}

  (secrets-config deploy-opts)
  ;; => "{}\n"

  (string/split-lines (traefik-config deploy-opts))
  ;; => ["global:"
  ;;     "  checkNewVersion: false"
  ;;     "  sendAnonymouseUsage: false"
  ;;     "accessLog:"
  ;;     "  filePath: /data/access.log"
  ;;     "log:"
  ;;     "  filePath: /data/traefik.log"
  ;;     "entryPoints:"
  ;;     "  web:"
  ;;     "    address: :80"
  ;;     "  websecure:"
  ;;     "    address: :443"
  ;;     "certifacesResolvers:"
  ;;     "  lets-encrypt:"
  ;;     "    acme:"
  ;;     "      email: martinklepsch@googlemail.com"
  ;;     "      storage: /data/acme.json"
  ;;     "      keyType: RSA2048"
  ;;     "      caServer: https://acme-staging-v02.api.letsencrypt.org/directory"
  ;;     "      httpChallenge:"
  ;;     "        entryPoint: web"
  ;;     "api:"
  ;;     "  dashboard: true"
  ;;     "  insecure: true"
  ;;     "ping: {}"
  ;;     "providers:"
  ;;     "  consulCatalog:"
  ;;     "    prefix: traefik"
  ;;     "    exposedByDefault: false"
  ;;     "    watch: true"]

  (local-test (nomad-post! "/v1/jobs" (jobspec deploy-opts)))
  ;; => {"EvalID" "761d1a71-7e1a-e9ee-2446-73d989f11be8",
  ;;     "EvalCreateIndex" 2647,
  ;;     "JobModifyIndex" 2647,
  ;;     "Warnings" "",
  ;;     "Index" 2647,
  ;;     "LastContact" 0,
  ;;     "KnownLeader" false,
  ;;     "NextToken" ""}

  (local-test (nomad-get (str "/v1/evaluation/" "761d1a71-7e1a-e9ee-2446-73d989f11be8")))
  ;; => {"CreateIndex" 2647,
  ;;     "Type" "service",
  ;;     "ModifyTime" 1731087546320243109,
  ;;     "JobModifyIndex" 2647,
  ;;     "Status" "complete",
  ;;     "Priority" 50,
  ;;     "TriggeredBy" "job-register",
  ;;     "ID" "761d1a71-7e1a-e9ee-2446-73d989f11be8",
  ;;     "Namespace" "default",
  ;;     "JobID" "cljdoc",
  ;;     "QueuedAllocations" {"cljdoc" 0, "lb" 0},
  ;;     "CreateTime" 1731087546288256351,
  ;;     "ModifyIndex" 2649,
  ;;     "SnapshotIndex" 2647,
  ;;     "DeploymentID" "fe58cd9d-49f3-d085-ac86-ad9bff51c235"}

  (local-test (nomad-get (str "/v1/deployment/" "fe58cd9d-49f3-d085-ac86-ad9bff51c235")))
  ;; => {"CreateIndex" 2648,
  ;;     "TaskGroups"
  ;;     {"cljdoc"
  ;;      {"PlacedCanaries" nil,
  ;;       "HealthyAllocs" 0,
  ;;       "RequireProgressBy" "2024-11-08T12:49:06.301603243-05:00",
  ;;       "UnhealthyAllocs" 0,
  ;;       "DesiredCanaries" 0,
  ;;       "AutoPromote" false,
  ;;       "AutoRevert" false,
  ;;       "Promoted" false,
  ;;       "DesiredTotal" 1,
  ;;       "ProgressDeadline" 600000000000,
  ;;       "PlacedAllocs" 1},
  ;;      "lb"
  ;;      {"PlacedCanaries" nil,
  ;;       "HealthyAllocs" 0,
  ;;       "RequireProgressBy" "2024-11-08T12:49:06.301603243-05:00",
  ;;       "UnhealthyAllocs" 0,
  ;;       "DesiredCanaries" 0,
  ;;       "AutoPromote" false,
  ;;       "AutoRevert" false,
  ;;       "Promoted" false,
  ;;       "DesiredTotal" 1,
  ;;       "ProgressDeadline" 600000000000,
  ;;       "PlacedAllocs" 1}},
  ;;     "JobModifyIndex" 2647,
  ;;     "JobVersion" 0,
  ;;     "Status" "running",
  ;;     "JobCreateIndex" 2647,
  ;;     "JobSpecModifyIndex" 2647,
  ;;     "StatusDescription" "Deployment is running",
  ;;     "ID" "fe58cd9d-49f3-d085-ac86-ad9bff51c235",
  ;;     "Namespace" "default",
  ;;     "JobID" "cljdoc",
  ;;     "IsMultiregion" false,
  ;;     "ModifyIndex" 2648,
  ;;     "EvalPriority" 50}

  (local-test
   (deploy! deploy-opts))

  :eoc)
