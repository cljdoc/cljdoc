(ns ^:slow ^:integration cljdoc.cli-integration-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :as t]
            [matcher-combinators.test]))

(t/deftest ingest-and-offline-bundle-test
  (process/shell "bb ingest --project bidi --version 2.1.3")
  (fs/delete-if-exists "target/bidi-bundle.zip")
  (fs/create-dirs "target")
  (process/shell "bb offline-bundle --project bidi --version 2.1.3 --output target/bidi-bundle.zip")
  (let [dest-dir "target/test/cli-integration"]
    (fs/delete-tree dest-dir)
    (fs/create-dirs dest-dir)
    (fs/unzip "target/bidi-bundle.zip" dest-dir)
    (let [files (->> (fs/glob dest-dir "**")
                     (filterv (complement fs/directory?))
                     (mapv #(fs/relativize dest-dir %))
                     (mapv str)
                     sort)]
      (t/is (match?
              ["bidi-2.1.3/api/bidi.bidi.html"
               "bidi-2.1.3/api/bidi.ring.html"
               "bidi-2.1.3/api/bidi.router.html"
               "bidi-2.1.3/api/bidi.schema.html"
               "bidi-2.1.3/api/bidi.verbose.html"
               "bidi-2.1.3/api/bidi.vhosts.html"
               "bidi-2.1.3/assets/cljdoc.css"
               "bidi-2.1.3/assets/highlightjs/highlight.min.js"
               "bidi-2.1.3/assets/highlightjs/languages/asciidoc.min.js"
               "bidi-2.1.3/assets/highlightjs/languages/clojure-repl.min.js"
               "bidi-2.1.3/assets/highlightjs/languages/clojure.min.js"
               #"bidi-2.1.3/assets/js/cljdoc.[0-9a-z]{8}.js.map"
               "bidi-2.1.3/assets/js/index.js"
               "bidi-2.1.3/assets/static/codeberg.svg"
               "bidi-2.1.3/assets/static/sourcehut.svg"
               "bidi-2.1.3/assets/tachyons.css"
               "bidi-2.1.3/doc/bidi-patterns.html"
               "bidi-2.1.3/doc/changelog.html"
               "bidi-2.1.3/doc/readme.html"
               "bidi-2.1.3/index.html"]
              files)))))
