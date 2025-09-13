(ns cljdoc.util.version
  "Use Maven as the authority for comparing versions"
  (:import [org.apache.maven.artifact.versioning ComparableVersion]))

(set! *warn-on-reflection* true)

(defn version-compare
  "Compares string versions `x` to `y` descending"
  [x y]
  (.compareTo (ComparableVersion. y) (ComparableVersion. x)))
