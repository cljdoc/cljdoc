(ns cljdoc.renderers.transit
  (:require [cljdoc.cache]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]))

;; Implement rendering cache to single transit file

(defrecord TransitRenderer []
  cljdoc.cache/CacheRenderer
  (render [_ cache {:keys [file] :as out-cfg}]
    (assert (and (instance? java.io.File file)
                 (nil? (:dir out-cfg))) "TransitRenderer expects file to render to")
    (-> (io/output-stream file)
        (transit/writer :json)
        (transit/write cache))))

(comment
  (cljdoc.cache/render (->TransitRenderer) cljdoc.cache/cache {:file (io/file "test.transit")})

  )
