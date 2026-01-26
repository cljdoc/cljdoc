#!/usr/bin/env bb

(ns ops.nomad
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]))

(let [spec {:identity-file {:desc "ssh identitify file"
                            :alias :i}}
      {:keys [args opts]} (cli/parse-args *command-line-args*
                                          {:restrict true
                                           :spec spec})]

  (when (not= 1 (count args))
    (println "Usage: nomad.clj <username@ip> [--identity-file, -i <identity-file>]")
    (System/exit 1))

  (let [user-ip (first args)
        socket (fs/create-temp-file {:prefix "deploy-ssh-socket-"})
        {:keys [identity-file]} opts]
    (fs/delete socket)

    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (when (fs/exists? socket)
          (println)
          (println "Cleaning up: Sending exit signal to SSH process")
          (try
            (p/shell {:inherit true} "ssh -S" socket "-O" "exit" user-ip)
            (catch Exception _
              ;; Ignore errors during cleanup
              nil))))))

    (println "Starting SSH port forwarding...")
    (let [portforward-cmd (cond-> ["ssh"
                                   "-M" "-S" socket "-fNT"
                                   "-L" "8080:localhost:8080"
                                   "-L" "8500:localhost:8500"
                                   "-L" "4646:localhost:4646"
                                   "-L" "9010:localhost:9010"]
                            identity-file (conj "-i" identity-file)
                            :always (conj user-ip))]
      (apply p/shell {:inherit true}
             portforward-cmd))

    ;; Check SSH connection
    (p/shell {:inherit true}
             "ssh -S" socket "-O" "check" user-ip)

    ;; Print information
    (println "You can now open Nomad or Consul:")
    (println "Nomad: http://localhost:4646/")
    (println "Consul: http://localhost:8500/")
    (println "Traefik: http://localhost:8080/")
    (println (str "SSH: ssh " user-ip))

    (println "")
    (println "Ctrl-C to quit")

    ;; prevent natural exit
    @(promise)))

