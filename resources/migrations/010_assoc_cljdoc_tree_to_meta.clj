(ns migrations.010-assoc-cljdoc-tree-to-meta
  (:require [clojure.java.jdbc :as sql]
            [taoensso.nippy :as nippy]))

(defn migrate-meta [db-spec update-doc-tree]
  (doseq [{:keys [id meta]}
          (map (fn [r] (update-in r [:meta :doc] update-doc-tree))
               (sql/query db-spec ["select id, meta from versions"]
                          {:row-fn (fn [r] (update r :meta nippy/thaw))}))]
    (sql/execute! db-spec
                  ["UPDATE versions SET meta = ? WHERE id = ?" (nippy/freeze meta) id])))

(defn up [db-spec]
  (migrate-meta
   db-spec
   (fn [doc-tree]
     (if (:cljdoc.doc/articles doc-tree)
       doc-tree
       {:cljdoc.doc/articles doc-tree}))))

(defn down [db-spec]
  (migrate-meta
   db-spec
   (fn [doc-tree]
     (if (:cljdoc.doc/articles doc-tree)
       (:cljdoc.doc/articles doc-tree)
       doc-tree))))
