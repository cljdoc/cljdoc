(require '[boot.pod :as pod])

(defn jar-file [coordinate]
  ;; (jar-file '[org.martinklepsch/derivatives "0.2.0"])
  (->> (pod/resolve-dependencies {:dependencies [coordinate]})
       (filter #(= coordinate (:dep %)))
       (first)
       :jar))

(deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file."]
  (boot.core/with-pre-wrap fileset
    (let [d (tmp-dir!)]
      (pod/unpack-jar jar d)
      (-> fileset (boot.core/add-resource d) commit!))))

(deftask build-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (comp (copy-jar-contents :jar (jar-file [project version]))
        ;; (show :fileset true)
        (target :dir #{"jar-contents"}) ;; `target` is a shortcut/hack here
        (with-pass-thru fs
          (let [cdx-pod (pod/make-pod {:dependencies [[project version]
                                                      '[codox "0.10.3"]]})]
            (pod/with-eval-in cdx-pod
              (require 'codox.main)
              (->> {:name         ~(name project)
                    :version      ~version
                    :description  "description"
                    :source-paths ["jar-contents"]
                    :output-path  ~(str (name project) "-docs")
                    :source-uri   nil
                    :doc-paths    nil
                    :language     nil
                    :namespaces   nil
                    :metadata     nil
                    :writer       nil
                    :exclude-vars nil
                    :themes       nil}
                   (remove (comp nil? second))
                   (into {})
                   (codox.main/generate-docs)))))))

;; Example invocation:
;; boot build-docs --project org.martinklepsch/derivatives --version 0.2.0

;; NEXT STEPS (mostly getting more information from Git repo)
;; - read Github URL from pom.xml or Clojars
;; - clone repo, copy `doc` directory, provide to codox
;; - derive source-uri (probably needs parsing of project.clj or build.boot or perhaps we can derive source locations by overlaying jar contents)
;; - figure out what other metadata should be imported
