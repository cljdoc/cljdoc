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
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [unilog.config :as unilog])
  (:import (com.jcraft.jsch JSch)
           (java.util.concurrent TimeUnit)))

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
    (= desired-total healthy-allocs)))

(defn- tag-exists?
  "Return true if given `tag` exists in the DockerHub cljdoc/cljdoc repository."
  [tag]
  (let [status (:status (http/head (format "https://hub.docker.com/v2/repositories/cljdoc/cljdoc/tags/%s/" tag)
                                   {:throw-exceptions false}))]
    (log/info "check for existence of docker tag" tag "returned" status)
    (= 200 status)))

(defn jobspec [docker-tag]
  (aero/read-config (io/resource "cljdoc.jobspec.edn")
                    {::opts {:docker-tag docker-tag}}))

(defn sync-config! []
  (doseq [[k v] {"config/traefik-toml" (slurp (io/resource "traefik.toml"))
                 "config/cljdoc/secrets-edn" (with-out-str
                                               (pp/pprint
                                                (aero/read-config
                                                 (io/resource "secrets.edn"))))}]

    (log/info "Syncing configuration:" k)
    (consul-put! k v)))

(defn deploy!
  "Deploy the specified docker tag to the Nomad instance listening on
  localhost:4646.

  This assumes that either the port has been forwarded from a remote
  host or that Nomad is running on localhost."
  [docker-tag]
  (sync-config!)
  (let [run-result (nomad-post! "/v1/jobs" (jobspec docker-tag))
        eval-id (get run-result "EvalID")
        eval (nomad-get (str "/v1/evaluation/" eval-id))
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

(defmacro with-nomad [{:keys [ip ssh-key ssh-user]} & body]
  `(let [jsch#    (JSch.)
         session# (.getSession jsch# ~ssh-user ~ip)]
     (.addIdentity jsch# ~ssh-key)
     (JSch/setConfig "StrictHostKeyChecking" "no")
     (.connect session# 5000)
     (try
       (.setPortForwardingL session# 8500 "localhost" 8500)
       (.setPortForwardingL session# 4646 "localhost" 4646)
       ~@body
       (finally
         (.disconnect session#)))))

(defn cli-deploy! [{:keys [ssh-key ssh-user docker-tag nomad-ip]}]
  (wait-until (format "docker tag %s exists" docker-tag) #(tag-exists? docker-tag)
              2000 (.toSeconds TimeUnit/MINUTES 3))
  (let [ip (or nomad-ip (main-ip))]
    (log/infof "Deploying to Nomad server at %s:4646" ip)
    (with-nomad {:ip ip, :ssh-key ssh-key :ssh-user ssh-user}
      (deploy! docker-tag))))

(def CONFIGURATION
  {:app         {:command     "cljdoc-deploy"
                 :description "command-line utilities to deploy cljdoc"
                 :version     "0.0.1"}
   :commands    [{:command     "deploy"
                  :description ["Deploy cljdoc to production"]
                  :opts        [{:option "ssh-key" :short "k" :as "SSH private key to use for accessing host" :type :string :default "~/.ssh/id_rsa"}
                                {:option "ssh-user" :short "u" :as "SSH user" :type :string :default :present}
                                {:option "nomad-ip" :as "IP of Nomad cluster to deploy to" :type :string}
                                {:option "docker-tag" :short "t" :as "Tag of cljdoc/cljdoc image to deploy" :type :string :default :present}]
                  :runs        cli-deploy!}]})

(defn -main
  [& args]
  (cli-matic/run-cmd args CONFIGURATION))

(comment
  (defn my-pred [succeed-after]
    (let [tries-left (atom succeed-after)]
      (fn []
        (zero? (swap! tries-left dec)))))

  ;; good after 3 tries
  (wait-until "foobar" (my-pred 3) 500 5)

  (wait-until "foobar" (my-pred 4) 500 763)

  :eoc)
