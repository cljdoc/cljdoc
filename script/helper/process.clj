(ns helper.process
  (:require [babashka.process :as ps]
            [clojure.pprint :as pprint]
            [lread.status-line :as status]))

(defn unchecked-process
  "Thin wrapper on babashka.process/process that does not exit on error."
  ([cmd] (unchecked-process cmd {}))
  ([cmd opts]
   (binding [ps/*defaults* (merge ps/*defaults* {:in :inherit
                                                 :out :inherit
                                                 :err :inherit})]
     @(ps/process cmd opts))))

(defn process
  "Thin wrapper on babashka.process/process that prints error message and exits on error."
  ([cmd] (process cmd {}))
  ([cmd opts]
   (let [{:keys [exit] :as res} (unchecked-process cmd opts)]
     (if (not (zero? exit))
       (status/die exit
                   "shell exited with %d for:\n %s"
                   exit (with-out-str (pprint/pprint cmd)))
       res))))
