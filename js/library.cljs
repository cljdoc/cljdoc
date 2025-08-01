(ns library)

(defn docsUri [{:keys [group-id artifact-id version] :as foo}]
  (.log console "docsUri" foo)
  (str "/d/" group-id "/" artifact-id "/" version))

(defn project [{:keys [group-id artifact-id]}]
  (if (= group-id artifact-id)
    group-id
    (str group-id "/" artifact-id)))
