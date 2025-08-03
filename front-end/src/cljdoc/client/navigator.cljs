;; used on ./versions page - which I think no one uses!
(ns cljdoc.client.navigator)

(defn onsubmit [e]
  (.preventDefault e)
  (let [form (.getElementById js/document "cljdoc-navigator")
        project (some-> (.querySelector form "[name=project]") .-value)
        version (some-> (.querySelector form "[name=version]") .-value)]
    (when (and project (pos? (.-length project)))
      (set! (.-href js/window.location)
            (if (.includes project "/")
              (str "/d/" project "/" version)
              (str "/d/" project "/" project "/" version))))))

(defn- form-field [{:keys [name label tip placeholder]}]
  #jsx [:fieldset {:class "fl w-50-ns pa2 bn mh0"}
        [:label {:class "b db mb3"} label
         [:span {:class "normal ml2 gray f6"} tip]]
        [:input {:name name
                 :class "w-90 pa2 b--blue br2 ba no-outline"
                 :auto-correct "off"
                 :autocapitalize "none"
                 :placeholder placeholder}]])

(defn Navigator []
  #jsx [:<>
        [:form {:id "cljdoc-navigator"
                :onsubmit onsubmit}
         [:div
          [:div {:class "cf nl2 nr2"}
           (form-field {:name "project"
                        :label "Group ID / Artifact ID"
                        :tip "may be identical"
                        :placeholder "e.g. 're-frame' or 'ring/ring-core"})
           (form-field {:name "version"
                        :label "Version"
                        :tip "optional"
                        :placeholder "e.g. '1.0.2'"})]
          [:input
           {:class "bg-blue white bn pv2 ph3 br2"
            :type "submit"
            :value "Go to Documentation"}]]]])
