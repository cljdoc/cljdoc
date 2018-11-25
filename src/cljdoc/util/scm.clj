(ns cljdoc.util.scm
  "Utilities to extract information from SCM urls (GitHub et al)"
  (:require [clojure.string :as string]
            [lambdaisland.uri :as uri]))

(defn owner [scm-url]
  (get (string/split (:path (uri/uri scm-url)) #"/" ) 1))

(defn repo [scm-url]
  (get (string/split (:path (uri/uri scm-url)) #"/" ) 2))

(defn coordinate [scm-url]
  (->> (string/split (:path (uri/uri scm-url)) #"/" )
       (filter seq)
       (string/join "/")))

(defn provider [scm-url]
  (let [host (:host (uri/uri scm-url))]
    (cond
      (.endsWith host "github.com") :github
      (.endsWith host "gitlab.com") :gitlab)))
