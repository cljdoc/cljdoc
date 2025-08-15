(ns cljdoc.render.components
  "There is a lot of repitive styling in cljdoc.
  This is the start of an effort to make some reusable components")

(defn link-button [opts & body]
  [:a.di.link.white.bg-blue.ph2.pv1.br2.mt2.pointer.hover-bg-dark-blue.lh-copy
   opts body])
