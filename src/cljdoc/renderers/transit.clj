(ns cljdoc.renderers.transit
  (:require [cljdoc.cache]
            [cljdoc.spec]
            [cljdoc.routes]
            [clojure.spec.alpha :as spec]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]))

;; Implement rendering cache to single transit file

(defrecord TransitRenderer []
  cljdoc.cache/ICacheRenderer
  (render [_ cache {:keys [dir] :as out-cfg}]
    (spec/assert :cljdoc.spec/cache-bundle cache)
    (assert (and dir
                 (.isDirectory dir)
                 (nil? (:file out-cfg))) "TransitRenderer expects dir to render to")
    (let [route (cljdoc.routes/path-for :transit/version (:cache-id cache))
          file  (io/file dir (subs route 1))]
      (io/make-parents file)
      (-> (io/output-stream file)
          (transit/writer :json)
          (transit/write cache)))))

(comment
  (cljdoc.cache/render (->TransitRenderer) cljdoc.cache/cache {:file (io/file "test.transit")})

  )
