(ns cljdoc.server.pedestal-test
  (:require [cljdoc.server.pedestal :as p]
            [clojure.string :as s]
            [clojure.test :as t]))

(t/deftest build-static-resource-map-test
  (let [resource-map (p/build-static-resource-map "cljdoc/resources/cljdoc_test.html")
        dot-count (fn [str] (->> str seq (filter #(= % \.)) count))]
    (t/is (= (count resource-map) 8))
    (doseq [[original content-hashed] resource-map]
      (t/is (s/starts-with? original "/"))
      (t/is (s/starts-with? content-hashed "/"))
      (t/is (= (+ (count original) 9) (count content-hashed))) ;additional dot + length of the content hash (8) = 9
      (t/is (= (+ (dot-count original) 1) (dot-count content-hashed))))))
