(ns cljdoc.client.mobile
  (:require [cljdoc.client.dom :as dom]
            ["preact/hooks" :refer [useState]]))

(defn MobileNav []
  (let [[scroll-pos-main set-scroll-pos-main] (useState nil)
        [scroll-pos-nav set-scroll-pos-nav] (useState nil)
        [show-nav set-show-nav] (useState nil)
        toggle-nav (fn []
                     (let [display "dn"
                           hide "db"
                           main-view (dom/query-doc ".js--main-scroll-view")
                           main-sidebar (dom/query-doc ".js--main-sidebar")
                           is-nav-shown? (and main-view
                                              (dom/has-class? main-view display))]
                       (if is-nav-shown?
                         (let [scroll-pos (.-scrollY js/window)]
                           (dom/remove-class main-view display)
                           (when main-sidebar
                             (dom/replace-class main-sidebar hide display))
                           (.scrollTo js/window 0 scroll-pos-main)
                           (set-show-nav false)
                           (set-scroll-pos-nav scroll-pos))
                         (let [scroll-pos (.-scrollY js/window)]
                           (when main-view (dom/add-class main-view display))
                           (when main-sidebar
                             (dom/add-class main-sidebar "flex-grow-1")
                             (dom/replace-class main-sidebar display hide))
                           (.scrollTo js/window 0 scroll-pos-nav)
                           (set-show-nav true)
                           (set-scroll-pos-main scroll-pos)))))]
    #jsx [:<>
          [:div {:class "bg-light-gray"}
           [:button {:class "outline-0 bw0 bg-transparent w-100 tl pa2"
                     :onClick toggle-nav}
            [:img {:class "dib mr2 v-mid"
                   :src (str "https://microicon-clone.vercel.app/"
                             (if show-nav "chevronLeft" "list")
                             "/32")
                   :height 32}]
            [:span {:class "dib"}
             (if show-nav
               "Back to Content"
               "Tap for Articles & Namespaces")]]]]))
