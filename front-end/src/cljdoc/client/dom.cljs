(ns cljdoc.client.dom)

(defn query-doc
  ([q elem]
   (when elem (.querySelector elem q)))
  ([q] (query-doc q js/document)))

(defn query-doc-all
  ([q elem]
   (when elem
     (.querySelectorAll elem q)))
  ([q] (query-doc-all q js/document)))

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
