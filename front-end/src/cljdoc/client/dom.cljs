(ns cljdoc.client.dom)

(defn query
  ([q elem]
   (when elem (.querySelector elem q)))
  ([q] (query q js/document)))

(defn query-all
  ([q elem]
   (when elem
     (.querySelectorAll elem q)))
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
