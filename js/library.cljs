(ns library)

(defn docsUri [{:keys [group_id artifact_id version] :as foo}]
  (.log console "docsUri" foo)
  (str "/d/" + group_id + "/" artifact_id + "/" version))

(defn project [{:keys [group_id artifact_id]}]
  (if (= group_id artifact_id)
    group_id
    (str group_id "/" artifact_id)))
