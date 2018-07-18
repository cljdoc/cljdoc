(ns cljdoc.util.deps
  (:require [clojure.java.io :as io]
            [version-clj.core :as v]
            [cljdoc.util.pom :as pom]))

(defn ensure-recent-ish [deps-map]
  (let [min-versions {'org.clojure/clojure "1.9.0"
                      'org.clojure/core.async "0.4.474"}
        choose-version (fn choose-version [given-v min-v]
                         (if (pos? (v/version-compare min-v given-v)) min-v given-v))]
    (reduce (fn [dm [proj min-v]]
              (cond-> dm
                (get dm proj) (update-in [proj :mvn/version] choose-version min-v)))
            deps-map
            min-versions)))

(defn extra-deps
  "Some projects require additional depenencies that have either been specified with
  scope 'provided' or are specified via documentation, e.g. a README.
  Maybe should be able to configure this via their cljdoc.edn configuration
  file but this situation being an edge case this is a sufficient fix for now."
  [pom]
  (->> (pom/dependencies (pom/parse (slurp pom)))
       (keep (fn [{:keys [group-id artifact-id version]}]
               (when-not (or (.startsWith artifact-id "boot-")
                             (and (= group-id "org.clojure")
                                  (= artifact-id "clojure")
                                  (or (not (.startsWith version "1.9"))
                                      (not (.startsWith version "1.10")))))
                 [(symbol group-id artifact-id) {:mvn/version version}])))
       (into {})))

(defn deps [pom project version]
  (-> {project {:mvn/version version}
       'cljdoc/analysis-impl {:local/root (.getAbsolutePath (io/file "." "modules" "analysis-impl"))}}
      (merge (extra-deps pom))
      (ensure-recent-ish)))

(comment
  (deps "/Users/martin/.m2/repository/manifold/manifold/0.1.6/manifold-0.1.6.pom" 'manifold/manifold "0.1.6")

  )
