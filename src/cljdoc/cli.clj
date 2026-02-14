(ns cljdoc.cli
  (:require [babashka.cli :as cli]
            [cljdoc.config :as config]
            [cljdoc.render.offline :as offline]
            [cljdoc.server.api :as api]
            [cljdoc.server.built-assets :as built-assets]
            [cljdoc.server.system :as system]
            [cljdoc.storage.api :as storage]
            [cljdoc.util.repositories :as repositories]
            [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn build [{:keys [opts]}]
  (let [{:keys [project version]} opts
        sys (select-keys (system/system-config (config/config))
                         [:cljdoc/db-spec
                          :cljdoc/db
                          :cljdoc/storage
                          :cljdoc/build-tracker
                          :cljdoc/analysis-service])
        sys (ig/init sys)
        service-opts {:storage (:cljdoc/storage sys)
                      :build-tracker (:cljdoc/build-tracker sys)
                      :analysis-service (:cljdoc/analysis-service sys)
                      :maven-repositories (config/get-in (config/config) [:maven-repositories])}]
    (deref
     (:future
      (api/kick-off-build!
       service-opts
       (-> (merge (repositories/local-uris project version) opts)
           (cset/rename-keys {:git :scm-url, :rev :scm-rev})))))))

(defn offline-docset [{:keys [opts]}]
  (let [{:keys [project version output]} opts
        sys           (select-keys (system/system-config (config/config))
                                   [:cljdoc/db-spec
                                    :cljdoc/db
                                    :cljdoc/storage])
        sys           (ig/init sys)
        store         (:cljdoc/storage sys)
        artifact-info (storage/version-entity project version)
        static-resources (built-assets/load-map)]
    (if (storage/exists? store artifact-info)
      (let [output (io/file output)]
        (-> (storage/load-docset store artifact-info)
            (offline/zip-stream static-resources)
            (io/copy output))
        (println "Offline docset created:" (.getCanonicalPath output)))
      (do
        (log/fatalf "%s@%s could not be found in storage" project version)
        (System/exit 1)))))

(defn run [_opts]
  (system/-main))

(def cmds-help "Usage: <command> [options...]

Commands:

ingest           Ingest and build a docset for an artifact at a specific version
offline-docset   Build an offline documentation set for a previously ingested artifact
run              Run the cljdoc server (config in resources/config.edn)

Use <command> --help for help on command")

(defn kw-opt->cli-opt
  [kw-opt]
  (let [opt (name kw-opt)]
    (if (= 1 (count opt))
      (str "-" opt)
      (str "--" opt))))

(defn- opts->table
  "Customized bb cli opts->table for cljdoc"
  [{:keys [spec order]}]
  (mapv (fn [[long-opt {:keys [default default-desc desc extra-desc ref require]}]]
          (let [option (kw-opt->cli-opt long-opt)
                default-shown (or default-desc
                                  default)
                attribute (or (when require "*required*")
                              default-shown)
                desc-shown (cond-> [(if attribute
                                      (str desc " [" attribute "]")
                                      desc)]
                             extra-desc (into extra-desc))]
            [(str option (when ref (str " " ref)))
             (str/join "\n " desc-shown)]))
        (let [order (or order (keys spec))]
          (map (fn [k] [k (spec k)]) order))))

(defn format-opts
  "Customized bb cli format-opts for cljdoc"
  [{:as cfg}]
  (cli/format-table {:rows (opts->table cfg) :indent 1}))

(defn error-text [text]
  (str "\u001B[31m" text "\u001B[0m"))

(defn opts-error-msg [{:keys [cause msg option]}]
  ;; Override default: options in cmdline syntax, not as keywords
  (cond
    (= :require cause)
    (str "Missing required option: " (kw-opt->cli-opt option))
    (= :restrict cause)
    (str "Unrecognized option: " (kw-opt->cli-opt option))
    :else msg))

(def ingest-spec
  {:project
   {:desc "Project to import"
    :alias :p
    :coerce :string
    :require true}
   :version
   {:desc "Version to import"
    :alias :v
    :coerce :string
    :require true}
   :jar
   {:desc "Jar file to use (local or remote)"
    :alias :j
    :default-desc "automatically resolved from --project and --version"
    :coerce :string}
   :pom
   {:desc "Pom file to use (local or remote)"
    :default-desc "automatically resolved from --project and --version"
    :coerce :string}
   :git
   {:desc "Git repository (local or remote)"
    :default-desc "automatically discovered in pom"
    :alias :g
    :coerce :string}
   :rev
   {:desc "Git revision"
    :default-desc "automatically discovered in pom or git tag representing --version"
    :alias :r
    :coerce :string}})
(def ingest-opt-order [:project :version :jar :pom :git :rev])

(def offline-docset-spec
  {:project
   {:desc "Project docset to export"
    :alias :p
    :require true}
   :version
   {:desc "Project version docset to export"
    :alias :v
    :require true}
   :output
   {:desc "Path of output zipfile"
    :alias :o
    :require true}})
(def offline-docset-opt-order [:project :version :output])

(def table
  [{:cmd "ingest"
    :fn build
    :spec ingest-spec
    :usage-opt-order ingest-opt-order}
   {:cmd "offline-docset"
    :cmd-alias "offline-bundle" ;; legacy, identical to offline-docset
    :fn offline-docset
    :spec offline-docset-spec
    :usage-opt-order offline-docset-opt-order}
   {:cmd "run"
    :fn run}])

(defn cmd-def-from-cmd [cmd-table cmd-find]
  (some (fn [{:keys [cmd cmd-alias] :as cmd-def}]
          (when (or (= cmd cmd-find)
                    (and cmd-alias (= cmd-alias cmd-find)))
            cmd-def))
        cmd-table))

(defn parse-cmd-opts-args
  [cli-args opts]
  (let [{:keys [args opts]} (cli/parse-args cli-args opts)]
    {:cmd (first args)
     :args (rest args)
     :opts opts}))

(defn parse-cmd [cmd-table cli-args]
  (let [{:keys [cmd args]} (parse-cmd-opts-args cli-args {})
        cmd-def (cmd-def-from-cmd cmd-table cmd)]
    (cond
      (nil? cmd)
      {:errors [{:msg "must specify a command"}]}

      (nil? cmd-def)
      {:errors [{:msg (str "invalid command: " cmd)}]}

      (seq args)
      {:cmd (:cmd cmd-def) :errors [{:msg (str "Command does not accept args, but found: " (str/join " " args))}]}

      :else
      {:cmd (:cmd cmd-def)})))

(defn cmds-help-requested [cli-args]
  (let [{:keys [cmd opts]} (parse-cmd-opts-args cli-args {:aliases {:h :help}})]
    (when (or (not (seq cli-args))
              (and (not (seq opts)) (= "help" cmd))
              (and (not cmd) (= {:help true} opts)))
      cmds-help)))

(defn cmd-usage-help [{:keys [spec usage-opt-order cmd]}]
  (if (seq usage-opt-order)
    (str "Usage: " cmd " <options..>\n\nOptions:\n\n"
         (format-opts {:spec spec :order usage-opt-order}))
    (str "Usage: " cmd "\n\nOptions: none for this command")))

(defn cmd-help-requested [cmd-table cli-args]
  (let [{:keys [cmd opts]} (parse-cmd-opts-args cli-args {:aliases {:h :help}})]
    (when (and (cmd-def-from-cmd cmd-table cmd) (:help opts))
      (cmd-usage-help (cmd-def-from-cmd cmd-table cmd)))))

(defn errors-as-text [errors usage-help]
  (str (error-text "ERRORS:") "\n"
       (reduce (fn [acc e]
                 (str acc " - " e "\n"))
               ""
               errors)
       "\n"
       usage-help
       "\n"))

(defn sort-errors
  "Sort errors by msg then by usage-opt-order"
  [cmd-def all-errors]
  (let [opt-order (zipmap (:usage-opt-order cmd-def) (range))]
    (sort (fn [x y]
            (let [x-opt-order (get opt-order (:option x))
                  y-opt-order (get opt-order (:option y))
                  c (compare x-opt-order y-opt-order)]
              (if (not= 0 c)
                c
                (compare (:msg x) (:msg y)))))
          all-errors)))

(defn main*
  "Separated out for testing. `:dipatch-fn` supports testing and overrides cmd dispatch `:fn`, set to, for example `identity`."
  [cli-args {:keys [dispatch-fn]}]
  ;; bb cli has a dispatch, but it can't currenlty do what we want, so we do our own thing
  (if-let [help (cmds-help-requested cli-args)]
    {:out help}
    (let [opt-errors (atom [])
          cmd-table (mapv (fn [{:keys [spec] :as d}]
                            (assoc d
                                   :spec (assoc spec :help {:alias :h})
                                   :error-fn (fn opts-error-fn [{:keys [msg option] :as data}]
                                               (if-let [refined-msg (opts-error-msg data)]
                                                 (swap! opt-errors conj {:option option :msg refined-msg})
                                                 (throw (ex-info msg data))))
                                   :restrict true))
                          table)]
      (if-let [help (cmd-help-requested cmd-table cli-args)]
        {:out help}
        (let [{:keys [cmd errors]} (parse-cmd cmd-table cli-args)
              cmd-def (cmd-def-from-cmd cmd-table cmd)
              cmd-opts-args (when cmd-def (parse-cmd-opts-args cli-args cmd-def))
              all-errors (cond-> []
                           errors (into errors)
                           @opt-errors (into @opt-errors))]
          (if (seq all-errors)
            {:out (errors-as-text (->> all-errors
                                       (sort-errors cmd-def)
                                       (mapv :msg))
                                  (if cmd-def
                                    (cmd-usage-help cmd-def)
                                    cmds-help))
             :exit 1}
            ((or dispatch-fn (:fn cmd-def)) cmd-opts-args)))))))

(defn -main
  [& cli-args]
  (let [{:keys [exit out]} (main* cli-args {})]
    (when out
      (println out))
    (if exit
      (System/exit exit)
      (shutdown-agents))))
