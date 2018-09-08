(ns cljdoc.util.deps
  (:require [clojure.java.io :as io]
            [version-clj.core :as v]
            [cljdoc.util :as util]
            [cljdoc.util.pom :as pom]))

(defn- ensure-recent-ish [deps-map]
  (let [min-versions {'org.clojure/clojure "1.9.0"
                      'org.clojure/clojurescript "1.10.339"
                      'org.clojure/java.classpath "0.2.2"
                      'org.clojure/tools.namespace "0.2.11"
                      'org.clojure/core.async "0.4.474"}
        choose-version (fn choose-version [given-v min-v]
                         (if (pos? (v/version-compare min-v given-v)) min-v given-v))]
    (reduce (fn [dm [proj min-v]]
              (cond-> dm
                (get dm proj) (update-in [proj :mvn/version] choose-version min-v)))
            deps-map
            min-versions)))

(defn- ensure-required-deps [deps-map]
  (merge {'org.clojure/clojure {:mvn/version "1.9.0"}
          'org.clojure/java.classpath {:mvn/version "0.2.2"}
          'org.clojure/tools.namespace {:mvn/version "0.2.11"}
          'org.clojure/clojurescript {:mvn/version "1.10.238"}}
         deps-map))

(defn- add-cljdoc-codox [deps-map]
  (assoc deps-map 'codox/codox {:exclusions '[enlive hiccup org.pegdown/pegdown]
                                ;; :mvn/version "0.10.4"
                                :git/url "https://github.com/martinklepsch/codox"
                                :sha "4b0720941083fda9643d905f0854fabea55b175f"
                                :deps/root "codox/"}))

(defn- hardcoded-deps [project]
  (-> '{"clj-time" {"clj-time" {org.clojure/java.jdbc {:mvn/version "0.7.7"}}}
        "com.taoensso" {"tufte" {com.taoensso/timbre {:mvn/version "4.10.0"}}}}
      (get-in [(util/group-id project) (util/artifact-id project)])))

(defn- extra-deps
  "Some projects require additional depenencies that have either been specified with
  scope 'provided' or are specified via documentation, e.g. a README.
  Maybe should be able to configure this via their cljdoc.edn configuration
  file but this situation being an edge case this is a sufficient fix for now."
  [pom]
  (->> (pom/dependencies (pom/parse (slurp pom)))
       (keep (fn [{:keys [group-id artifact-id version]}]
               (when-not (or (.startsWith artifact-id "boot-")
                             ;; Ensure that tools.reader version is used as specified by CLJS
                             (and (= group-id "org.clojure")
                                  (= artifact-id "tools.reader"))
                             ;; The version can be nil when pom's utilize dependencyManagement - this unsurprisingly breaks tools.deps
                             ;; Remains to be seen if this causes any issues
                             ;; http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
                             (nil? version))
                 [(symbol group-id artifact-id) {:mvn/version version}])))
       (into {})))

(defn deps [pom project version]
  (-> (extra-deps pom)
      (merge (hardcoded-deps project))
      (ensure-required-deps)
      (add-cljdoc-codox)
      (ensure-recent-ish)
      (assoc project {:mvn/version version})))

(comment
  (deps "/Users/martin/.m2/repository/manifold/manifold/0.1.6/manifold-0.1.6.pom" 'manifold/manifold "0.1.6")

  (deps "https://repo.clojars.org/lambdaisland/kaocha/0.0-113/kaocha-0.0-113.pom" 'lambdaisland/kaocha "0.0-113")

  (with-deps-edn {:deps {}} (io/file "."))

  )
