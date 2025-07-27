(ns dom)

(defn query-doc
  ([q elem]
   (when elem (.querySelector elem q)))
  ([q] (query-doc q document)))

(defn query-doc-all
  ([q elem]
   (.log console "qsa" q elem)
   (when elem
     (.querySelectorAll elem q)))
  ([q] (query-doc-all q document)))

(defn remove-class [el c]
  (.remove (.-classList el) c))

(defn replace-class [el old new]
  (.replace (.-classList el) old new))

(defn add-class [el c]
  (.add (.-classList el) c))

(defn has-class? [el c]
  (.contains (.-classList el) c))
