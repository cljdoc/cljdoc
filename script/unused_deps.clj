(ns unused-deps
  (:require [babashka.tasks :as tasks]
            [clj-commons.format.table :as table]
            [lread.status-line :as status]))

(defn task [_opts]
  (let [;; manually update explanations as needed
        explanations {'org.asciidoctor/asciidoctorj "actual API is in dep org.asciidoctor/asciidoctorj-api which we do not explicity depend on"
                      'dev.weavejester/ragtime "ragtime API is in its dependencies"}
        unused-deps (->> (tasks/exec 'borkdude.unused-deps/unused-deps)
                         :unused-deps
                         sort
                         (mapv (fn [d] (-> {:dep (str d)}
                                           (assoc :explanation (get explanations (first d)))))))]
    (status/line :detail "Dependencies that do not seem to be referenced by code")
    (status/line :detail "NOTE: Update explanation in script/unused_deps.clj as you see fit")
    (status/line :detail "")
    (table/print-table [{:key :dep         :title-align :left :title "Dependency"  :align :left}
                        {:key :explanation :title-align :left :title "Explanation" :align :left}]
                       unused-deps)
    (status/line :detail "Total unused deps with no explanation: %d" (count (filter (fn [r] (-> r :explanation nil?)) unused-deps)))))
