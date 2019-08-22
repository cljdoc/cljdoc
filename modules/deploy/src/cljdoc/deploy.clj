(ns cljdoc.deploy
  "This namespace schedules the cljdoc Clojure service and the Traefik
  load balancer via Nomad.

  It communicates with Nomad and Consul by establishing an SSH port forwarding
  so the Nomad and Consul ports can be accessed on the local machine.

  The deploy is happening in two stages:

  1. Create a new Nomad deployment with the new version running as a canary.
  2. Promote the Nomad deployment, resulting in a shut down of previous deployments."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.java.shell :as sh]
            [clojure.string :as string]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [cli-matic.core :as cli-matic]
            [unilog.config :as unilog])
  (:import (java.time Instant)
           (com.jcraft.jsch JSch)))

(unilog/start-logging! {:level :info :console true})

(defn- git-root []
  (let [proc (sh/sh "git" "rev-parse" "--show-toplevel")]
    (assert (zero? (:exit proc)))
    (-> proc :out string/trim)))

(defn- main-ip []
  (let [state-arg (str "-state=" (git-root) "/ops/infrastructure/terraform.tfstate")
        proc (sh/sh "terraform"  "output" state-arg "-json")]
    (assert (zero? (:exit proc)))
    (-> proc :out json/parse-string (get-in ["main_ip" "value"]))))

(def nomad-base-uri
  "http://localhost:4646")

(defn nomad-get [path]
  (json/parse-string (:body (http/get (str nomad-base-uri path)))))

(defn nomad-post! [path body]
  (->> (http/post (str nomad-base-uri path)
                  {:body (json/generate-string body)})
       :body
       json/parse-string))

(defn consul-put! [k v]
  (->> (http/put (str "http://localhost:8500/v1/kv/" k)
                 {:body v})
       :body
       json/parse-string))

(defn consul [path]
  (str "http://localhost:8500" path))

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

(defn- wait-until [pred test-interval tries]
  (cond
    (pred)       true
    (pos? tries) (do (Thread/sleep test-interval)
                     (recur pred test-interval (dec tries)))
    :else        (throw (Exception. "wait-until pred took too long"))))

(defn promote-deployment! [deployment-id]
  (nomad-post! (str "/v1/deployment/promote/" deployment-id)
               {"DeploymentID" deployment-id
                "All" true}))

(defn deployment-healthy? [deployment-id]
  (let [deployment     (nomad-get (str "/v1/deployment/" deployment-id))
        desired-total  (get-in deployment ["TaskGroups" "cljdoc" "DesiredTotal"])
        healthy-allocs (get-in deployment ["TaskGroups" "cljdoc" "HealthyAllocs"])
        placed-allocs  (get-in deployment ["TaskGroups" "cljdoc" "PlacedAllocs"])
        status         (get-in deployment ["Status"])]
    (assert (= placed-allocs desired-total) "Not enough allocs placed")
    (assert (not= "failed" status) "Deployment failed")
    (log/infof "%d healthy / %d desired - status: '%s'" healthy-allocs desired-total status)
    (and (= desired-total healthy-allocs))))

(defn tag-exists?
  "Check if a given tag exists in the DockerHub cljdoc/cljdoc repository."
  [tag]
  (= 200 (:status (http/head (format "https://hub.docker.com/v2/repositories/cljdoc/cljdoc/tags/%s/" tag)
                             {:throw-exceptions? false}))))

(defn jobspec [docker-tag]
  (aero/read-config (io/resource "cljdoc.jobspec.edn")
                    {::opts {:docker-tag docker-tag}}))

(defn sync-config! []
  (doseq [[k v] {"config/traefik-toml" (slurp (io/resource "traefik.toml"))
                 "config/cljdoc/secrets-edn" (with-out-str
                                               (pp/pprint
                                                (aero/read-config (io/resource "secrets.edn"))))}]

    (log/info "Syncing configuration:" k)
    (consul-put! k v)))

(defn deploy!
  "Deploy the specified docker tag to the Nomad instance listening on
  localhost:4646.

  This assumes that either the port has been forwarded from a remote
  host or that Nomad is running on localhost."
  [docker-tag]
  (assert (tag-exists? docker-tag) (format "Provided tag '%s' could not be found" docker-tag))
  (sync-config!)
  (let [run-result (nomad-post! "/v1/jobs" (jobspec docker-tag))
        eval-id (get run-result "EvalID")
        eval (nomad-get (str "/v1/evaluation/" eval-id))
        deployment-id (get eval "DeploymentID")]
    (assert deployment-id "Deployment ID missing")
    (log/info "Evaluation ID:" eval-id)
    (log/info "Deployment ID:" deployment-id)
    (wait-until #(deployment-healthy? deployment-id) 5000 40)
    (let [deployment (nomad-get (str "/v1/deployment/" deployment-id))]
      (if (= "running" (get deployment "Status"))
        (do
          (log/info "Promoting:" deployment-id)
          (promote-deployment! deployment-id))
        (log/info "Nothing to do for deployment status" (pr-str (get deployment "Status")))))))

(defmacro with-nomad [{:keys [ip ssh-key]} & body]
  `(let [jsch#    (JSch.)
         session# (.getSession jsch# "root" ~ip)]
    (.addIdentity jsch# ~ssh-key)
    (JSch/setConfig "StrictHostKeyChecking" "no")
    (.connect session# 5000)
     (try
       (.setPortForwardingL session# 8500 "localhost" 8500)
       (.setPortForwardingL session# 4646 "localhost" 4646)
       ~@body
       (finally
         (.disconnect session#)))))

(defn cli-deploy! [{:keys [ssh-key docker-tag nomad-ip]}]
  (let [ip (or nomad-ip (main-ip))]
    (log/infof "Deploying to Nomad server at %s:4646" ip)
    (if (tag-exists? docker-tag)
      (with-nomad {:ip ip, :ssh-key ssh-key}
        (deploy! docker-tag))
      (do
        (log/errorf "Provided tag '%s' could not be found" docker-tag)
        (System/exit 1)))))

(def CONFIGURATION
  {:app         {:command     "cljdoc-deploy"
                 :description "command-line utilities to deploy cljdoc"
                 :version     "0.0.1"}
   :commands    [{:command     "deploy"
                  :description ["Deploy cljdoc to production"]
                  :opts        [{:option "ssh-key" :short "k" :as "SSH private key to use for accessing host" :type :string :default "~/.ssh/id_rsa"}
                                {:option "nomad-ip" :as "IP of Nomad cluster to deploy to" :type :string}
                                {:option "docker-tag" :short "t" :as "Tag of cljdoc/cljdoc image to deploy" :type :string :default :present}]
                  :runs        cli-deploy!}]})

(defn -main
  [& args]
  (cli-matic/run-cmd args CONFIGURATION))

(comment

  (with-nomad ip
    (nomad-get "/v1/deployments")
    (deploy!
     (or "0.0.1160-blue-green-8b4cdad" "0.0.1151-blue-green-c329ed1")))

  )
