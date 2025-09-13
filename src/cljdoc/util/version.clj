(ns cljdoc.util.version
  "Use Maven as the authority for comparing versions"
  (:import [org.apache.maven.artifact.versioning ComparableVersion]))

(set! *warn-on-reflection* true)

(defn version-compare
  "Compares string versions `x` to `y` descending"
  [x y]
  (.compareTo (ComparableVersion. y) (ComparableVersion. x)))

(comment
  (version-sort)

  (->> ["2-beta31.1"
        "2-beta54"
        "2-beta54-SNAPSHOT"
        "2-beta54-alpha1"
        "2-beta54-alpha2.1"
        "2-beta54-alpha2"
        "2-beta54-alpha3"
        "2-beta54-rc1"
        "2-beta53"
        "2-beta52"
        "2-beta51"
        "2-beta50"
        "2-beta49"
        "2-beta48"
        "2-beta47"
        "2-beta46"
        "2-beta45"
        "2-beta44"
        "2-beta43"
        "2-beta42"
        "2-beta41"
        "2-beta40"
        "2-beta39"
        "2-beta38"
        "2-beta37"
        "2-beta36"
        "2-beta35"
        "2-beta34"
        "2-beta33"
        "2-beta32"
        "2-beta30"
        "2-beta29"
        "2-beta28"]
       shuffle
       (sort version-compare))
  ;; => ("2-beta54"
  ;;     "2-beta54-SNAPSHOT"
  ;;     "2-beta54-rc1"
  ;;     "2-beta54-alpha3"
  ;;     "2-beta54-alpha2.1"
  ;;     "2-beta54-alpha2"
  ;;     "2-beta54-alpha1"
  ;;     "2-beta53"
  ;;     "2-beta52"
  ;;     "2-beta51"
  ;;     "2-beta50"
  ;;     "2-beta49"
  ;;     "2-beta48"
  ;;     "2-beta47"
  ;;     "2-beta46"
  ;;     "2-beta45"
  ;;     "2-beta44"
  ;;     "2-beta43"
  ;;     "2-beta42"
  ;;     "2-beta41"
  ;;     "2-beta40"
  ;;     "2-beta39"
  ;;     "2-beta38"
  ;;     "2-beta37"
  ;;     "2-beta36"
  ;;     "2-beta35"
  ;;     "2-beta34"
  ;;     "2-beta33"
  ;;     "2-beta32"
  ;;     "2-beta31.1"
  ;;     "2-beta30"
  ;;     "2-beta29"
  ;;     "2-beta28")

  :eoc)
