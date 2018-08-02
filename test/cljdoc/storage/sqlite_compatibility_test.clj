(ns cljdoc.storage.sqlite-compatibility-test
  (:require [cljdoc.server.system :as system]
            [cljdoc.config :as cfg]
            [cljdoc.util :as util]
            [cljdoc.util.codox :as codox-util]
            [integrant.core :as ig]
            [cljdoc.storage.sqlite-import :as sqlite-import]
            [cljdoc.storage.sqlite-impl :as sqlite]
            [cljdoc.storage.api :as storage]
            [clojure.test :as t]))

(defn setup-sqlite! [tests]
  (let [conf (cfg/config :test)
        sys  (ig/init
              {:cljdoc/sqlite {:db-spec (cfg/build-log-db conf)
                               :dir     (cfg/data-dir conf)}})]
    (tests)
    (ig/halt! sys)
    (util/delete-directory! (java.io.File. (cfg/data-dir conf)))))

(t/use-fixtures :each setup-sqlite!)

(def cases
  [#_{:group-id "" :artifact-id "" :version "" :analysis-file ""}
   {:group-id "jarohen" :artifact-id "nomad" :version "0.9.0-alpha9" :analysis-file "https://2950-119377591-gh.circle-artifacts.com/0/cljdoc-edn/jarohen/nomad/0.9.0-alpha9/cljdoc.edn"}
   {:group-id "amazonica" :artifact-id "amazonica" :version "0.3.130" :analysis-file "https://2395-119377591-gh.circle-artifacts.com/0/cljdoc-edn/amazonica/amazonica/0.3.130/cljdoc.edn"}
   {:group-id "adamrenklint" :artifact-id "prost" :version "1.2.0" :analysis-file "https://2945-119377591-gh.circle-artifacts.com/0/cljdoc-edn/adamrenklint/prost/1.2.0/cljdoc.edn"}
   {:group-id "coreagile" :artifact-id "itl" :version "0.0.9" :analysis-file "https://2942-119377591-gh.circle-artifacts.com/0/cljdoc-edn/coreagile/itl/0.0.9/cljdoc.edn"}
   {:group-id "stavka" :artifact-id "stavka" :version "0.4.1" :analysis-file "https://2941-119377591-gh.circle-artifacts.com/0/cljdoc-edn/stavka/stavka/0.4.1/cljdoc.edn"}
   {:group-id "techascent" :artifact-id "tech.io" :version "0.1.2" :analysis-file "https://2919-119377591-gh.circle-artifacts.com/0/cljdoc-edn/techascent/tech.io/0.1.2/cljdoc.edn"}
   {:group-id "za.co.simply" :artifact-id "compojure.claims" :version "1.0.0" :analysis-file "https://2952-119377591-gh.circle-artifacts.com/0/cljdoc-edn/za.co.simply/compojure.claims/1.0.0/cljdoc.edn"}])

(defn test-bundle-equality [grim-bundle sqlite-bundle]
  (println "Testing equality" (:cache-id grim-bundle))
  (t/is (= (:cache-id grim-bundle) (:cache-id sqlite-bundle)))
  (t/is (= (-> grim-bundle :cache-contents :defs)
           (-> sqlite-bundle :cache-contents :defs)))
  (t/is (= (-> grim-bundle :cache-contents :namespaces)
           (-> sqlite-bundle :cache-contents :namespaces)))
  (t/is (= (-> grim-bundle :cache-contents :version)
           (-> sqlite-bundle :cache-contents :version)))
  (t/is (= (-> grim-bundle :cache-contents)
           (-> sqlite-bundle :cache-contents))))

(t/deftest grimoire-sqlite-api-roundtrip-equality
  (let [sqlite   (storage/->SQLiteStorage (cfg/build-log-db (cfg/config :test)))
        grimoire (storage/->GrimoireStorage (cfg/grimoire-dir (cfg/config :test)))]
    (doseq [m cases]
      (let [codox-data (-> m :analysis-file slurp clojure.edn/read-string :codox codox-util/sanitize-macros)]
        (storage/import-api sqlite m codox-data)
        (storage/import-api grimoire m codox-data))
      (let [grim-bundle (storage/bundle-docs grimoire m)
            sqlite-bundle (storage/bundle-docs sqlite m)]
        (test-bundle-equality grim-bundle sqlite-bundle)))))

(t/deftest grimoire-to-sqlite-import-test
  (let [db-spec  (cfg/build-log-db (cfg/config :test))
        grimoire-dir (cfg/grimoire-dir (cfg/config :test))
        sqlite   (storage/->SQLiteStorage db-spec)
        grimoire (storage/->GrimoireStorage grimoire-dir)]
    (doseq [m cases]
      (println "Importing" m)
      (let [codox-data (-> m :analysis-file slurp clojure.edn/read-string :codox codox-util/sanitize-macros)]
        (storage/import-api grimoire m codox-data))
      (sqlite-import/import-version db-spec grimoire-dir m)
      (let [grim-bundle   (storage/bundle-docs grimoire m)
            sqlite-bundle (storage/bundle-docs sqlite m)]
        (test-bundle-equality grim-bundle sqlite-bundle)))))

(comment
  (t/run-tests)

  (require 'clojure.inspector)

  (def nomad (:codox (clojure.edn/read-string (slurp "https://2950-119377591-gh.circle-artifacts.com/0/cljdoc-edn/jarohen/nomad/0.9.0-alpha9/cljdoc.edn"))))

  (let [db-spec  (cfg/build-log-db (cfg/config :test))
        grimoire-dir (cfg/grimoire-dir (cfg/config :test))
        m {:group-id "jarohen" :artifact-id "nomad" :version "0.9.0-alpha9" :analysis-file "https://2950-119377591-gh.circle-artifacts.com/0/cljdoc-edn/jarohen/nomad/0.9.0-alpha9/cljdoc.edn"}]
    (storage/import-api (storage/->GrimoireStorage (cfg/grimoire-dir (cfg/config :test)))
                        m
                        (-> m :analysis-file slurp clojure.edn/read-string :codox codox-util/sanitize-macros))
    (sqlite-import/import-version db-spec grimoire-dir m))

  (clojure.inspector/inspect-tree nomad)

  (defn dups [seq]
    (for [[id freq] (frequencies seq)  ;; get the frequencies, destructure
          :when (> freq 1)]            ;; this is the filter condition
      id))

  (for [[platf namespaces] nomad]
    [platf (map :name namespaces)])

  (defn- macro? [var]
    (= :macro (:type var)))


  (clojure.inspector/inspect-tree (sanitize-macros nomad))

  #_(defn sanitize-codox-publics [publics]
    (let [by-name (group-by :name publics)]
      (map (fn [[name publics-for-name]]
             (if (= 1 (count publics-for-name))
               (first publics-for-name)
               (do (assert (every? macro publics-for-name)
                           (format "Non-macro duplicates %s" publics-by-name))
                   (let [without-form-env-special])
                   )
               )
             ))
      )
    )
  (get-in nomad ["cljs" 0 :publics])

  (dups
   (sort-by :line
    (for [[platf namespaces] nomad
          ns namespaces
          var (:publics ns)
          ;; :when (= 'switch (:name var))
          ]


      #_[platf (:name ns) (:name var)]
      (-> (assoc var :platform platf :namespace (name (:name ns)))
          (select-keys [:name :namespace :platform]))))

   )

  (clojure.inspector/inspect-tree
   (group-by :name
             (for [[platf namespaces] nomad
                   ns namespaces
                   var (:publics ns)
                   ;; :when (= 'defconfig (:name var))
                   ]


               #_[platf (:name ns) (:name var)]
               (-> (assoc var :platform platf :namespace (name (:name ns)))
                   #_(select-keys [:name :namespace :platform])))))

  nomad

  #_(doseq [var (for [[platf namespaces] nomad
                    ns namespaces
                    var (:publics ns)]
                (assoc var :platform platf :namespace (name (:name ns))))]
    (when (= 'defconfig (:name var))
      (println 'write-var! (select-keys var [:name :namespace :platform]))
      (select-keys var [:name :namespace :platform])))

  (spit "nomad.edn" (with-out-str (clojure.pprint/pprint (:codox (clojure.edn/read-string (slurp "https://2950-119377591-gh.circle-artifacts.com/0/cljdoc-edn/jarohen/nomad/0.9.0-alpha9/cljdoc.edn"))))))

  )
