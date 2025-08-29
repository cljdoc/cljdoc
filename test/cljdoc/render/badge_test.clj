(ns cljdoc.render.badge-test
  "Spot checks on badge generation, mostly verifying sizing success/failure color.
  Each test spits out its html to ./target/badge-test/ dir for manual visual inspection in your browser."
  (:require [babashka.fs :as fs]
            [cljdoc.render.badge :as badge]
            [clojure.test :as t]
            [matcher-combinators.test])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Document Element]))

(set! *warn-on-reflection* true)

(defn- attributes [^Element elem]
  (reduce (fn [acc ^Attribute n]
            (assoc acc (keyword (.getKey n)) (.getValue n)))
          {}
          (.asList (.attributes elem))))

(defn- select ^Element [^Document doc ^String selector]
  (.selectFirst doc selector))

(def ^:private out-dir "./target/badge-test")

(t/use-fixtures :once (fn [f]
                        (when (fs/exists? out-dir)
                          (fs/delete-tree out-dir))
                        (fs/create-dirs out-dir)
                        (f)))

(t/deftest happy-badge-test
  (let [actual (badge/cljdoc-badge "1.2.3-alpha72" :success)
        doc (Jsoup/parse actual)]
    (spit (fs/file out-dir "happy-badge-test.html") actual)
    (t/is (match? {:width "129.9" :height "20" :viewBox "0 0 1299 200"}
                  (-> (select doc "svg") attributes)))
    (t/is (match? {:fill "#08C" :x "418" :width "881"}
                  (-> (select doc "rect[x=418]") attributes))
          "badge text has correct size and success color")
    (t/is (= "cljdoc: 1.2.3-alpha72"
             (-> (select doc "svg title") .text)))
    (t/is (match? {:x "60" :y "148" :textLength "318"}
                  (-> (select doc "text:contains(cljdoc)") attributes)))
    (t/is (match? {:x "473" :y "148" :textLength "781"}
                  (-> (select doc "text:contains(1.2.3-alpha72)")
                      attributes)))))

(t/deftest sad-badge-test
  (let [actual (badge/cljdoc-badge "1.2.3-alpha72" :not-success)
        doc (Jsoup/parse actual)]
    (spit (fs/file out-dir "sad-badge-test.html") actual)
    (t/is (match? {:fill "#E43" :x "418" :width "881"}
                  (-> (select doc "rect[x=418]") attributes))
          "badge text has correct size and failure color")))

(t/deftest wide-char-test
  (let [badge-text "Hello \u0604 there \u102A friend \u0FD0"
        actual (badge/cljdoc-badge badge-text :success)
        doc (Jsoup/parse actual)]
    (spit (fs/file out-dir "wide-char-test.html") actual)
    (t/is (match? {:width "235.2" :height "20" :viewBox "0 0 2352 200"}
                  (-> (select doc "svg") attributes)))
    (t/is (match? {:fill "#08C" :x "418" :width "1934"}
                  (-> (select doc "rect[x=418]") attributes))
          "badge text has correct size and success color")
    (t/is (= (str "cljdoc: " badge-text)
             (-> (select doc "svg title") .text)))
    (t/is (match? {:x "60" :y "148" :textLength "318"}
                  (-> (select doc "text:contains(cljdoc)") attributes)))
    (t/is (match? {:x "473" :y "148" :textLength "1834"}
                  (-> (select doc (str "text:contains(" badge-text ")"))
                      attributes)))))
