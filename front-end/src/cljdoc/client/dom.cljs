(ns cljdoc.client.dom)

(warn-on-lazy-reusage!)

(defn query
  ([q el]
   (when el (.querySelector el q)))
  ([q] (query q js/document)))

(defn query-all
  ([q el]
   (when el
     (.querySelectorAll el q)))
  ([q] (query-all q js/document)))

(defn remove-class [el c]
  (.remove (.-classList el) c))

(defn replace-class [el old new]
  (.replace (.-classList el) old new))

(defn add-class [el c]
  (.add (.-classList el) c))

(defn has-class? [el c]
  (.contains (.-classList el) c))

(defn toggle-class [el c]
  (.toggle (.-classList el) c))
