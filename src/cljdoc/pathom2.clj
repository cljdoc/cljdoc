;; Another stab at using Pathom, this time just the core so
;; I can develop a better understanding of how it works
(ns cljdoc.pathom2
  (:require [com.wsscode.pathom.core :as p]
            [clojure.set]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.pom :as pom]
            [cljdoc.config :as config]
            [cljdoc.storage.sqlite-impl :as cljdoc-sqlite]))

(def computed
  {:artifact/pom-url
   (fn [env]
     (let [{:artifact/keys [name group version] :as x} (p/entity env)]
       (:pom (repos/artifact-uris (symbol group name) version))))

   :artifact/by-sql-id
   (fn [{:keys [db] :as env}]
     (let [{:artifact/keys [by-sql-id]} (p/entity env)]
       (#'cljdoc-sqlite/get-version db by-sql-id)))

   :artifact/sql-id
   (fn [{:keys [db] :as env}]
     (let [{:artifact/keys [name group version] :as x} (p/entity env)]
       (#'cljdoc-sqlite/get-version-id db group name version)))

   :artifact/dependencies
   (fn [env]
     (let [{:artifact/keys [pom-url]} (p/entity env [:artifact/pom-url])]
       (clojure.pprint/pprint (pom/dependencies (pom/parse (slurp pom-url))))
       (->> (pom/dependencies (pom/parse (slurp pom-url)))
            (filter :version) ;;dependencyManagement issues
            (map #(clojure.set/rename-keys % {:group-id :artifact/group
                                              :artifact-id :artifact/name
                                              :version :artifact/version}))
            (p/join-seq env))))})

(def cljdoc-parser
  (p/parser {::p/plugins [(p/env-plugin {::p/reader [p/map-reader computed]})]}))

(comment
  (def artifact #:artifact{:group "org.martinklepsch" :name "derivatives" :version "0.3.0"})

  (cljdoc-parser {::p/entity artifact
                  :db (config/db (config/config))}
                 '[:artifact/pom-url
                   :artifact/sql-id
                   {:artifact/dependencies [* :artifact/sql-id]}])

  )








;; EXAMPLE --------------------------------------------------------------------

(def rick
  #:character{:name          "Rick"
              :age           60
              :family        [#:character{:name "Morty" :age 14}
                              #:character{:name "Summer" :age 17}]
              :first-episode #:episode{:name "Pilot" :season 1 :number 1}})

(def char-name->voice
  "Relational information representing edges from character names to actors"
  {"Rick"   #:actor{:name "Justin Roiland" :nationality "US"}
   "Morty"  #:actor{:name "Justin Roiland" :nationality "US"}
   "Summer" #:actor{:name "Spencer Grammer" :nationality "US"}})

(def computed
  {:character/voice ; support an invented join attribute
   (fn [env]
     (let [{:character/keys [name]} (p/entity env)
           voice (get char-name->voice name)]
       (p/join voice env)))})

(def parser
                                        ; process with map-reader first, then try with computed
  (p/parser {::p/plugins [(p/env-plugin {::p/reader [p/map-reader computed]})]}))

(comment
  (parser {::p/entity rick} ; start with rick (as current entity)
          '[:character/name
            {:character/voice [:actor/name]}
            {:character/family [*]}])

  #:character{:name "Rick", :voice #:actor{:name "Justin Roiland"},
              :family [#:character{:name "Morty", :age 14, :voice #:actor{:name "Justin Roiland", :nationality "US"}}
                       #:character{:name "Summer", :age 17, :voice #:actor{:name "Spencer Grammer", :nationality "US"}}]})
