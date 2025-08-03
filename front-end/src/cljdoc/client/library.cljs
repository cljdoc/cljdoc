(ns cljdoc.client.library)

(defn docs-coords-path
  "Return coords portion of docs path for given `coords`"
  [{:keys [group-id artifact-id version] :as _coords}]
  (str group-id "/" artifact-id "/" version))

(defn docs-path
  "Return the full docs path for given `coords`"
  [coords]
  (str "/d/" (docs-coords-path coords)))

(defn project
  "Return project for given `coords`, use lein shortname if possible"
  [{:keys [group-id artifact-id]}]
  (if (= group-id artifact-id)
    group-id
    (str group-id "/" artifact-id)))

(defn parse-docs-uri
  "Return coords from `uri`"
  [uri]
  (let [[lead group-id artifact-id version] (rest (.split uri "/"))]
    (when (= "d" lead)
      {:group-id group-id
       :artifact-id artifact-id
       :version version})))

(defn coords-from-current-loc
  "Extract and return coords from current browser location"
  []
  (parse-docs-uri js/window.location.pathname))

(defn coords-path-from-current-loc
  "Extract and return coords path (without leading /d/) from current browser location"
  []
  (docs-coords-path (coords-from-current-loc)))
