(ns cljdoc.client.mobile
  "Support for mobile/small screens"
  (:require ["preact" :refer [h render]]
            ["preact/hooks" :refer [useState]]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.page :as page]))

(defn list-icon []
  #jsx [:svg {:xmlns "http://www.w3.org/2000/svg"
              :class "w1 h1 mr2"
              :viewBox "0 0 24 24"
              :fill "currentColor"}
        [:circle {:cx "5" :cy "6" :r "1.5"}]
        [:circle {:cx "5" :cy "12" :r "1.5"}]
        [:circle {:cx "5" :cy "18" :r "1.5"}]
        [:rect {:x "9" :y "5" :width "12" :height "2" :rx "1"}]
        [:rect {:x "9" :y "11" :width "12" :height "2" :rx "1"}]
        [:rect {:x "9" :y "17" :width "12" :height "2" :rx "1"}]])

(defn left-chevron-icon []
  #jsx [:svg {:xmlns "http://www.w3.org/2000/svg"
              :class "w1 h1 mr2"
              :viewBox "0 0 32 32"}
        [:path {:d "M20 1 L24 5 L14 16 L24 27 L20 31 L6 16 z"}]])

(defn MobileNav []
  (let [[scroll-pos-main set-scroll-pos-main] (useState nil)
        [scroll-pos-nav set-scroll-pos-nav] (useState nil)
        [show-nav set-show-nav] (useState nil)
        toggle-nav (fn []
                     (let [display "dn"
                           hide "db"
                           main-view (dom/query ".js--main-scroll-view")
                           main-sidebar (dom/query ".js--main-sidebar")
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
          [:div {:class "bg-light-gray "}
           [:button {:class "outline-0 bw0 bg-transparent w-100 tl pa2 flex items-center"
                     :onClick toggle-nav}
            (if show-nav (left-chevron-icon) (list-icon))
            [:span {:class "dib"}
             (if show-nav
               "Back to Content"
               "Tap for Articles & Namespaces")]]]]))

(defn init []
  (when (page/is-project-doc)
    (when-let [mobile-nav-node (dom/query "[data-id='cljdoc-js--mobile-nav']")]
      (render (h MobileNav) mobile-nav-node))))
