(ns hljs-merge-plugin)

(def original-stream (atom []))

(defn- escape-html [s]
  (-> s
      (.replace #"&" "&amp;")
      (.replace #"<" "&lt;")
      (.replace #">" "&gt;")
      (.replace #"\"" "&quot;")
      (.replace #"'" "&#x27;")))

(defn- tag [node]
  (-> node .-tagName .toLowerCase))

(defn- node-stream
  "Return a stream of events for children of `node`."
  [node]
  (let [void-tags #{"br" "hr" "img" "input"}
        events (atom [])
        walk (fn walk [node offset]
               (.log console "walk" node offset)
               (loop [child (.-firstChild node)
                      offset offset]
                 (.log console "walk-loop" child offset @events)
                 (if child
                   (case (.-nodeType child)
                     ;; Text node
                     3 (let [text (.-nodeValue child)]
                         (.log console "3-text" child text)
                         (recur (.-nextSibling child)
                                (if text
                                  (+ offset (count text))
                                  offset)))

                     ;; Element node
                     1 (let [tag (tag child)]
                         (.log console "1-tag" child tag)
                         (swap! events conj {:event "start" :offset offset :node child})
                         (let [new-offset (walk child offset)]
                           (when-not (void-tags tag)
                             (swap! events conj {:event "stop" :offset new-offset :node child}))
                           (recur (.-nextSibling child) new-offset)))

                     ;; Other node types
                     (do
                       (.log console "-other" child)
                       (recur (.-nextSibling child) offset)))
                   offset)))]
    (walk node 0)
    (.log console "events" @events)
    @events))

(defn- open-node [node]
  (let [tag-name (tag node)
        attrs (.-attributes node)
        attr-str (map (fn [attr]
                        (str " " (-name attr) "=\""
                             (escape-html (.value attr)) "\""))
                      (array-seq attrs))]
    (str "<" tag-name (apply str attr-str) ">")))

(defn- close-node [node]
  (str "</" (tag node) ">") )


(defn- render [event]
  (if (= (:event event) "start")
    (open-node (:node event))
    (close-node (:node event))))

(defn- select-stream [orig hl]
  (cond
    (and (empty? orig) (empty? hl)) nil
    (empty? orig)                   :hl
    (empty? hl)                     :orig
    :else (let [o1 (first orig)
                h1 (first hl)]
            (cond
              (not= (:offset o1) (:offset h1))
              (if (< (:offset o1) (:offset h1))
                :orig
                :hl)
              :else (if (= "start" (:event h1))
                      :orig
                      :hl)))))

(defn- merge-streams [original highlighted value]
  (loop [processed 0
         result ""
         node-stack []
         orig original
         hl highlighted]
    (let [stream-type (select-stream orig hl)]
      (.log console "stream-type" stream-type)
      (if (nil? stream-type)
        ;; Base case: both streams empty
        (str result (escape-html (subs value processed)))

        (let [stream (case stream-type :orig orig :hl hl)
              event (first stream)
              event-offset (:offset event)]
          ;; Append text between last processed offset and current event
          (if (> event-offset processed)
            (recur event-offset
                   (str result (escape-html (subs value processed event-offset)))
                   node-stack
                   orig
                   hl)

            ;; Process event at current offset
            (case stream-type
              :orig
              (let [;; Close all nodes in reverse order
                    closing-tags (apply str (map close-node (rseq (vec node-stack))))
                    new-result (str result closing-tags)

                    ;; Process consecutive original events at same offset
                    [after-orig-result new-orig]
                    (loop [res new-result
                           o orig
                           h hl]
                      (let [current (first o)]
                        (if (and current
                                 (= (:offset current) event-offset)
                                 (= :orig (select-stream o h)))
                          (recur (str res (render current))
                                 (rest o)
                                 h)
                          [res o])))

                    ;; Reopen nodes in original order
                    opening-tags (apply str (map open-node node-stack))
                    final-result (str after-orig-result opening-tags)]
                (recur event-offset
                       final-result
                       node-stack
                       new-orig
                       hl))

              :hl
              (let [new-result (str result (render event))
                    new-hl (rest hl)
                    new-stack (if (= "start" (:event event))
                                (conj node-stack (:node event))
                                (pop node-stack))]
                (recur event-offset
                       new-result
                       new-stack
                       orig
                       new-hl)))))))))

(def mergeHTMLPlugin
  {"before:highlightElement" (fn [{:keys [el]}]
                               (.log console "before:hl" el)
                               (reset! original-stream (node-stream el)))
  ;; merge it afterwards with the highlighted token stream
   "after:highlightElement" (fn [{:keys [el result text]}]
                              (.log console "after:hl" el result text @original-stream)
                              (when (.-length @original-stream)
                                (let [result-node (.createElement document "div")]
                                  (set! (.-innerHTML result-node) (.-value result))
                                  (set! (.-value result) (merge-streams @original-stream (node-stream result-node) text))
                                  (set! (.-innerHTML el) (.-value result)))))})
