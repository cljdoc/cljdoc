(ns cljdoc.server.search.api-test
  (:require [cljdoc.server.search.api :as api]
            [cljdoc.server.clojars-stats :as clojars-stats]
            [clojure.string :as string]
            [clojure.test :as t]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]
            [integrant.core :as ig])
  (:import (org.apache.lucene.store ByteBuffersDirectory)))

(def ^:dynamic *searcher* nil)
(def ^:dynamic *download-count-artifact* (constantly 1))
(def ^:dynamic *download-count-max* (constantly 100))

(defn- memory-index []
  (ByteBuffersDirectory.))

(defn search-fixture [f]
  (binding [*searcher* (ig/init-key :cljdoc/searcher {:enable-indexer? false
                                                      :index-factory memory-index
                                                      :clojars-stats (reify clojars-stats/IClojarsStats
                                                                       (download-count-max [_]
                                                                         (*download-count-max*))
                                                                       (download-count-artifact [_ g a]
                                                                         (*download-count-artifact* g a)))})]
    (try
      (f)
      (finally
        (ig/halt-key! *searcher* :cljdoc/searcher)))))

(t/use-fixtures :each search-fixture)

;; dotty modified slightly for test purposes
(def next-jdbc
  {:group-id "com.github.seancorfield"
   :artifact-id "next.jdbc"
   :description "The next generation of clojure.java.jdbc: a new low-level Clojure wrapper for JDBC-based access to databases."
   :origin :clojars
   :versions ["1.2.772" "1.2.1"]})

;; dashy
(def byte-transforms
  {:group-id "clj-commons"
   :artifact-id "byte-transforms"
   :description "Methods for hashing, compressing, and encoding bytes."
   :origin :clojars
   :versions ["0.1.4" "0.1.35"]})

(defn r [{:keys [versions] :as in}]
  (-> in
      (assoc :version (first versions))
      (assoc :blurb (:description in))
      (dissoc :versions :origin :description)))

(defn rs [& ins]
  {:count (count ins)
   :results (mapv r ins)})

(t/deftest emptiness
  (run! #(api/index-artifact *searcher* %) [next-jdbc byte-transforms])
  (t/is (match? (rs) (api/search *searcher* "   "))))

(t/deftest single-terms
  (run! #(api/index-artifact *searcher* %) [next-jdbc byte-transforms])
  (t/testing "group-id"
    (t/testing "matches prefix"
      (t/is (match? (rs byte-transforms) (api/search *searcher* "clj"))))
    (t/testing "matches on entire when contains dots"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "com.github.seancorfield"))))
    (t/testing "matches on entire when contains dashes"
      (t/is (match? (rs byte-transforms) (api/search *searcher* "clj-commons"))))
    (t/testing "matches start of a segment"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "sean"))))
    (t/testing "matches any substring within segment"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "corfiel")))))

  (t/testing "artifact-id"
    (t/testing "matches on entire when contains dots"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "next.jdbc"))))
    (t/testing "matches on entire when contains dashes"
      (t/is (match? (rs byte-transforms) (api/search *searcher* "byte-transforms")))))

  (t/testing "description"
    (t/testing "matches on word"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "databases"))))
    (t/testing "matches on word prefix"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "gener"))))
    (t/testing "matches on word substring"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "ataba"))))
    (t/testing "matches on hyphenated word"
      (t/is (match? (rs next-jdbc) (api/search *searcher* "level")))))

  (t/testing "description and group-id"
    ;; byte-transforms should appear first because it matches more fields
    (t/is (match? (rs byte-transforms next-jdbc) (api/search *searcher* "com")))))

(t/deftest multiple-terms
  (run! #(api/index-artifact *searcher* %) [next-jdbc byte-transforms])
  (t/testing "no match - terms must be in same doc"
    (t/is (match? (rs) (api/search *searcher* "gener encoding"))))
  (t/testing "match - terms all occur in single doc in description only"
    (t/is (match? (rs next-jdbc) (api/search *searcher* "next gener wrapper"))))
  (t/testing "no match - 3 of 4 terms occur in single doc in description only"
    (t/is (match? (rs) (api/search *searcher* "next gener wrapper nopenotindoc")))))

(t/deftest accents
  (let [a {:group-id "g1"
           :artifact-id "a1"
           :description "façade"
           :origin :clojars
           :versions ["1.2.3"]}
        b {:group-id "g2"
           :artifact-id "a2"
           :description "facade"
           :origin :clojars
           :versions ["1.2.3"]}]
    (run! #(api/index-artifact *searcher* %) [a b])
    ;; all things being equal, these should come back in index order?
    ;; if that's not the case, we'll adapt the test not care about result order.
    (t/is (match? (rs a b) (api/search *searcher* "facade")))
    (t/is (match? (rs a b) (api/search *searcher* "façade")))))

(defn sample-artifacts []
  (->> (for [ndx (range 1 101)]
         {:group-id (str "g" ndx)
          :artifact-id (str "a" ndx)
          :description (str "d" ndx)
          :origin :clojars
          :versions ["1.2.3"]})
       (into [])))

(t/deftest popularity-weighs
  (let [artifacts (sample-artifacts)]
    ;; reverse the order of population by assigning a download count
    ;; first item gets download count of 1, 2nd 2, and so on.
    (binding [*download-count-max* #(count artifacts)
              *download-count-artifact* (fn [g _a] (parse-long (string/replace g #"^\D*" "")))]
      (run! #(api/index-artifact *searcher* %) artifacts)
      (t/is (match? {:count (count artifacts) ;; total count, not number returned
                     :results (->> artifacts reverse (take 30) (mapv r))}
                    (api/search *searcher* "d"))))))

(defn add-prefix [k prefix coll]
  (mapv #(update % k (fn [s] (str prefix s)))
        coll))

(t/deftest exact-match-on-group-id-weighs-more-than-popularity
  (let [artifacts (->> (sample-artifacts)
                       (add-prefix :group-id "ma."))
        artifacts (assoc-in artifacts [0 :group-id] "ma")]
    ;; match on least popular doc, it should appear first
    (binding [*download-count-max* #(count artifacts)
              *download-count-artifact* (fn [g _a] (or (parse-long (string/replace g #"^\D*" "")) 0))]
      (run! #(api/index-artifact *searcher* %) artifacts)
      (t/is (match? {:count (count artifacts)
                     :results (->> artifacts
                                   reverse
                                   (take 29)
                                   (cons (first artifacts))
                                   (mapv r))}
                    (api/search *searcher* "ma"))))))

(t/deftest exact-match-on-artifact-id-weighs-more-than-popularity
  (let [artifacts (->> (sample-artifacts)
                       (add-prefix :artifact-id "ma."))
        artifacts (assoc-in artifacts [0 :artifact-id] "ma")]
    ;; least popular doc should appear first
    (binding [*download-count-max* #(count artifacts)
              *download-count-artifact* (fn [g _a] (or (parse-long (string/replace g #"^\D*" "")) 0))]
      (run! #(api/index-artifact *searcher* %) artifacts)
      (t/is (match? {:count (count artifacts)
                     :results (->> artifacts
                                   reverse
                                   (take 29)
                                   (cons (first artifacts))
                                   (mapv r))}
                    (api/search *searcher* "ma"))))))

(t/deftest exact-match-on-group-id-and-artifact-id-weighs-more-than-popularity
  (let [artifacts (->> (sample-artifacts)
                       (add-prefix :artifact-id "ma.")
                       (add-prefix :group-id "ma."))
        artifacts (update artifacts 0 merge {:artifact-id "ma"
                                             :group-id "ma"})]
    ;; least popular doc should appear first
    (binding [*download-count-max* #(count artifacts)
              *download-count-artifact* (fn [g _a] (or (parse-long (string/replace g #"^\D*" "")) 0))]
      (run! #(api/index-artifact *searcher* %) artifacts)
      (t/is (match? {:count (count artifacts)
                     :results (->> artifacts
                                   reverse
                                   (take 29)
                                   (cons (first artifacts))
                                   (mapv r))}
                    (api/search *searcher* "ma"))))))

(t/deftest match-on-description-weighs-less
  (let [artifacts (sample-artifacts)
        artifacts (update artifacts 51 merge {:artifact-id "g1.d"
                                              :group-id "a1.d"
                                              :description "x"})]
    ;; matches on description should appear after even partial matches group-id, artifact-id
    (run! #(api/index-artifact *searcher* %) artifacts)
    (let [result (api/search *searcher* "d")]
      (t/is (= (count artifacts) (:count result)))
      (t/is (= 30 (-> result :results count)))
      ;; which items match is not deterministic because they'll have the same score... but
      ;; the first match should be the one that also matched on group and artifact id
      (t/is (match? (r (nth artifacts 51)) (-> result :results first))))))

(t/deftest suggest
  (run! #(api/index-artifact *searcher* %) [next-jdbc byte-transforms])
  (t/testing "no match"
    (t/is (= ["nomatch" []] (api/suggest *searcher* "nomatch"))))
  (t/testing "empty"
    (t/is (= [" " []] (api/suggest *searcher* " "))))
  (t/testing "match"
    (t/is (= ["for" ["clj-commons/byte-transforms "
                     "com.github.seancorfield/next.jdbc "]] (api/suggest *searcher* "for")))))

(defn- expected-versions-result [indexed-artifacts]
  (->> indexed-artifacts
       (map (fn [{:keys [group-id artifact-id versions]}]
              {:group-id group-id
               :artifact-id artifact-id
               :versions (remove #(string/ends-with? % "-SNAPSHOT") versions)}))))

(t/deftest versions
  (let [g1-a1 {:group-id "g1"
               :artifact-id "a1"
               :description "g1-a1 has a snapshot release"
               :origin :clojars
               :versions ["1.1.0" "1.1.1" "1.1.2-SNAPSHOT"]}
        g1-a2 {:group-id "g1"
               :artifact-id "a2"
               :description "g1-a2"
               :origin :clojars
               :versions ["1.2.0" "1.2.1"]}
        g2-a1 {:group-id "g2"
               :artifact-id "a1"
               :description "g2-a1"
               :origin :clojars
               :versions ["2.1.0"]}
        g2-a2 {:group-id "g2"
               :artifact-id "a2"
               :description "g2-a2 all snapshot releases"
               :origin :clojars
               :versions ["2.2.0-SNAPSHOT" "2.2.1-SNAPSHOT"]}
        indexed [g1-a1 g1-a2 g2-a1 g2-a2]]
    (run! #(api/index-artifact *searcher* %) indexed)
    (t/testing "no refinements - return all"
      (t/is (match? (m/in-any-order (expected-versions-result indexed))
                    (api/artifact-versions *searcher* {}))))
    (t/testing "refine by group-id"
      (t/is (match? (m/in-any-order (expected-versions-result [g1-a1 g1-a2]))
                    (api/artifact-versions *searcher* {:group-id "g1"}))))
    (t/testing "refine by group-id and artifact-id"
      (t/is (= (expected-versions-result [g1-a2])
               (api/artifact-versions *searcher* {:group-id "g1" :artifact-id "a2"}))))
    (t/testing "refine by group-id and unknown artifact-id"
      (t/is (= (expected-versions-result [])
               (api/artifact-versions *searcher* {:group-id "g1" :artifact-id "nope"}))))))

(comment
  (def s (ig/init-key :cljdoc/searcher {:clojars-stats (reify clojars-stats/IClojarsStats
                                                         (download-count-max [_] 100)
                                                         (download-count-artifact [_ g a] 1))
                                        :index-factory memory-index :enable-indexer? false}))

  (run! #(api/index-artifact s %) [next-jdbc byte-transforms])

  (api/search s "com")
  (api/search s "next.jdbc")
  (api/search s "hashing")

  nil)
