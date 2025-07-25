;; used on ./versions page - which I think no one uses!

;; I'm a preact newb, but does it simplify or complicate here?
(ns navigator
  (:require ["preact/hooks" :refer [useRef]]))

(defn ^:private navigate [clojarsIdInput versionInput]
  (let [clojars-id (some-> clojarsIdInput .-current .-value)
        version (some-> versionInput .-current .-value)]
    (when (and clojars-id (pos? (.-length clojars-id)))
                                  (set! (.-href js/window.location)
                                        (if (.includes clojars-id "/")
                                          (str "/d/" clojars-id "/" version)
                                          (str "/d/" clojars-id "/" clojars-id "/" version))))))

(defn Navigator []
  (let [clojarsIdInput (useRef nil)
        versionInput (useRef nil)]
    #jsx [:<>
          [:div
           [:div {:class "cf nl2 nr2"}
            [:fieldset {:class "fl w-50-ns pa2 bn mh0"}
             [:label {:class "b db mb3"}
              "Group ID / Artifact ID"
              [:span {:class "normal ml2 gray f6"} "may be identical"]]
             [:input {:class "w-90 pa2 b--blue br2 ba no-outline"
                      :auto-correct "off"
                      :autocapitalize "none"
                      :onKeyUp (fn [e]
                                 (when (= "Enter" e.key) (navigate clojarsIdInput versionInput)))
                      :ref clojarsIdInput
                      :placeholder "e.g. 're-frame' or 'ring/ring-core"}]]
            [:fieldset {:class "fl w-50-ns pa2 bn mh0"}
             [:label {:class "b db mb3"}
              "Version"
              [:span {:class "normal ml2 gray f6"} "optional"]]
             [:input
              {:class "w-90 pa2 b--blue br2 ba no-outline"
               :auto-correct "off"
               :autocapitalize "none"
               :onKeyUp (fn [e]
                          (when (= "Enter" e.key) (navigate clojarsIdInput versionInput)))
               :ref versionInput
               :placeholder "e.g. 're-frame' or 'reing/ring-core"}]]]
           [:input
            {:class "bg-blue white bn pv2 ph3 br2"
             :type "button"
             :onClick (fn [] (navigate clojarsIdInput versionInput))
             :value "Go to Documentation"}]]]))
