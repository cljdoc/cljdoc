(ns hljs-copy-button-plugin
  "A plugin to add a copy button to code blocks
  There is https://github.com/arronhunt/highlightjs-copy but it seems to have some
  unresolved issues. There is not much code to this, so let's just do a bare bones ourselves.")

(def copyButtonPlugin
  {"before:highlightElement"
   (fn [{:keys [el]}]
     (let [button (.createElement js/document "button")]
       (set! (.-innerHTML button) "copy")
       (set! (.-className button) "hljs-copy-button")
       (set! (.-copied (.-dataset button)) "false")
       (let [parent (.-parentElement el)]
         (.add (.-classList parent) "hljs-copy-wrapper")
         (.appendChild parent button)
         (.addEventListener button "click"
                            (fn []
                              (when-let [clipboard js/navigator.clipboard]
                                (-> (.writeText clipboard (.-innerText el))
                                    (.then (fn []
                                             (set! (.-innerHTML button) "copied")
                                             (set! (.-copied (.-dataset button)) "true"))))
                                (js/setTimeout (fn []
                                                 (set! (.-innerHTML button) "copy")
                                                 (set! (.-copied (.-dataset button)) "false"))
                                               2000)))))))})
