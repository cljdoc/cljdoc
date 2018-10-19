;; Another stab at using Pathom, this time just the core so
;; I can develop a better understanding of how it works
(ns cljdoc.pathom2
  (:require [com.wsscode.pathom.core :as p]))

(def computed
  {:artifact/pom-url
   (fn [env]
     (let [{:artifact/keys [name group version] :as x} (p/entity env)]
       (str group "/" name "/" version)))
   :artifact/dependencies
   (fn [env]
     (let [{:artifact/keys [pom-url]} (p/entity env [:artifact/pom-url])]
       [#:artifact{:name "cljdoc"
                   :group "cljdoc"
                   :version "1.0.0"}
        #:artifact{:name "cljdoc"
                   :group "cljdoc"
                   :version "2.0.0"}]))})

(def cljdoc-parser
  (p/parser {::p/plugins [(p/env-plugin {::p/reader [p/map-reader computed]})]}))

(comment
  (def artifact #:artifact{:name  "name" :group "group" :version "1.1.10"})

  ;;
  (cljdoc-parser {::p/entity artifact}
                 '[:artifact/pom-url
                   {:artifact/dependencies [:artifact/version :artifact/pom-url]}])

  ;; I'd expect this return value:
  #:artifact{:pom-url "name/group/1.1.10"
             :dependencies [#:artifact{:version "..." :pom-url} ,,,]}

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
            {:character/family [* :character/voice]}])

  #:character{:name "Rick", :voice #:actor{:name "Justin Roiland"},
              :family [#:character{:name "Morty", :age 14, :voice #:actor{:name "Justin Roiland", :nationality "US"}}
                       #:character{:name "Summer", :age 17, :voice #:actor{:name "Spencer Grammer", :nationality "US"}}]})
