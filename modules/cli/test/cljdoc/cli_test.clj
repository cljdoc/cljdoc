(ns cljdoc.cli-test
  "Test command line parsing and validation."
  (:require
   [cljdoc.cli :as cli]
   [clojure.string :as str]
   [clojure.test :as t]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test]))

(defn main-test [args]
  (cli/main* args {:dispatch-fn identity}))

;;
;; help
;; 
(t/deftest cmds-usage-help-test
  (doseq [args [[]
                ["help"]
                ["-h"]]]
    (t/is (match? {:exit m/absent
                   :out #"(?s)^Usage: <command>.*\nCommands:"}
                  (main-test args))
          args)))

(t/deftest run-cmd-help-test
  (doseq [args [["run" "-h"]
                ["run" "--help"]]]
    (t/is (match? {:exit m/absent
                   :out #"(?s)^Usage: run\n\nOptions: none for this command$"}
                  (main-test args))
          args)))

(t/deftest ingest-cmd-help-test
  (doseq [args [["ingest" "-h"]
                ["ingest" "--help"]]]
    (t/is (match? {:exit m/absent
                   :out #"(?s)^Usage: ingest <options..>\n\nOptions:\n.*--project.*--rev"}
                  (main-test args))
          args)))

(t/deftest offline-docset-cmd-help-test
  (doseq [args [["offline-docset" "-h"]
                ["offline-docset" "--help"]
                ;; legacy old name
                ["offline-bundle" "-h"]
                ["offline-bundle" "--help"]
                ]]
    (t/is (match? {:exit m/absent
                   :out #"(?s)^Usage: offline-docset <options..>\n\nOptions:\n.*--project.*--output"}
                  (main-test args))
          args)))

;;
;; valid args
;;

(t/deftest run-cmd-test
  (t/is (match? {:exit m/absent
                 :out m/absent
                 :cmd "run" :opts {}}
                (main-test ["run"]))))

(t/deftest offline-bundle-legacy-cmd-test
  (doseq [args [["offline-bundle" "--project" "foo/bar" "--version" "1.2.3" "--output" "out-file.edn"]
                ["offline-bundle" "-p" "foo/bar" "-v" "1.2.3" "-o" "out-file.edn"]]]
    (t/is (match? {:exit m/absent :out m/absent
                   :cmd "offline-bundle" :opts (m/equals {:project "foo/bar" :version "1.2.3" :output "out-file.edn"})}
                  (main-test args)))))

(t/deftest offline-docset-cmd-test
  (doseq [args [["offline-docset" "--project" "foo/bar" "--version" "1.2.3" "--output" "out-file.edn"]
                ["offline-docset" "-p" "foo/bar" "-v" "1.2.3" "-o" "out-file.edn"]]]
    (t/is (match? {:exit m/absent :out m/absent
                   :cmd "offline-docset" :opts (m/equals {:project "foo/bar" :version "1.2.3" :output "out-file.edn"})}
                  (main-test args)))))

(t/deftest ingest-cmd-test
  (doseq [args [["ingest" "--project" "foo/bar" "--version" "1.2.3"]
                ["ingest" "-p" "foo/bar" "-v" "1.2.3"]]]
    (t/is (match? {:exit m/absent :out m/absent
                   :cmd "ingest" :opts (m/equals {:project "foo/bar" :version "1.2.3"})}
                  (main-test args))))
  (doseq [args [["ingest" "--project" "foo/bar" "--version" "1.2.3"
                 "--jar" "some-jar" "--pom" "some-pom"
                 "--git" "git-repo" "--rev" "some-revision"]
                ["ingest" "-p" "foo/bar" "-v" "1.2.3"
                 "-j" "some-jar" "--pom" "some-pom"
                 "-g" "git-repo" "-r" "some-revision"]]]
    (t/is (match? {:exit m/absent :out m/absent
                   :cmd "ingest" :opts (m/equals {:project "foo/bar" :version "1.2.3"
                                                  :jar "some-jar" :pom "some-pom"
                                                  :git "git-repo" :rev "some-revision"})}
                  (main-test args)))))

;;
;; invalid args
;;
(t/deftest cmd-missing-test
  (t/is (match? {:exit 1 
                 :out (re-pattern (str "^.*ERRORS.*\n"
                                       " - must specify a command.*\n"
                                       "\nUsage: <command>.*\n\n"
                                       "Commands:.*"))}
                (main-test ["--foo"]))))

(t/deftest run-error-test
  (t/is (match? {:exit 1
                 :out (re-pattern (str "^.*ERRORS.*\n"
                                       " - Command does not accept args, but found: some-arg\n"
                                       " - Unrecognized option: --bad-opt\n"
                                       "\nUsage: run\n\n"
                                       "Options: none for this command"))}
                (main-test ["run" "--bad-opt" "some-val" "some-arg"]))))

(t/deftest ingest-error-test
  (t/is (match? {:exit 1
                 :out (re-pattern (str "^.*ERRORS.*?\n"
                                       ;; TODO: ensure order consistent
                                       " - Command does not accept args, but found: some-arg\n"
                                       " - Unrecognized option: --bad-opt\n"
                                       " - Missing required option: --project\n"
                                       " - Missing required option: --version\n"
                                       "\nUsage: ingest <options..>\n\n"
                                       "Options:"))}
                (main-test ["ingest" "--bad-opt" "some-val" "some-arg"]))))

(t/deftest offline-docset-error-test
  (t/is (match? {:exit 1
                 :out (re-pattern (str "^.*ERRORS.*?\n"
                                       ;; TODO: ensure order consistent
                                       " - Command does not accept args, but found: some-arg\n"
                                       " - Unrecognized option: --bad-opt\n"
                                       " - Missing required option: --project\n"
                                       " - Missing required option: --version\n"
                                       " - Missing required option: --output\n"
                                       "\nUsage: offline-docset <options..>\n\n"
                                       "Options:"))}
                (main-test ["offline-docset" "--bad-opt" "some-val" "some-arg"]))))

(t/deftest offline-bundle-error-test
  (t/is (match? {:exit 1
                 :out (re-pattern (str "^.*ERRORS.*?\n"
                                       ;; TODO: ensure order consistent
                                       " - Command does not accept args, but found: some-arg\n"
                                       " - Unrecognized option: --bad-opt\n"
                                       " - Missing required option: --project\n"
                                       " - Missing required option: --version\n"
                                       " - Missing required option: --output\n"
                                       "\nUsage: offline-docset <options..>\n\n"
                                       "Options:"))}
                (main-test ["offline-bundle" "--bad-opt" "some-val" "some-arg"]))))
