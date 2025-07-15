(ns cljdoc.s3
  (:require [cljdoc.server.log.init] ;; to quiet odd jetty DEBUG logging
            [clojure.java.io :as io])
  (:import (java.lang AutoCloseable)
           (software.amazon.awssdk.auth.credentials AwsBasicCredentials AwsCredentialsProvider StaticCredentialsProvider)
           (software.amazon.awssdk.core.sync RequestBody ResponseTransformer)
           (software.amazon.awssdk.regions Region)
           (software.amazon.awssdk.services.s3 S3Client)
           (software.amazon.awssdk.services.s3.model CopyObjectRequest DeleteObjectRequest GetObjectRequest ListObjectsV2Request ObjectCannedACL PutObjectRequest S3Object)))

(set! *warn-on-reflection* true)

(defprotocol IObjectStore
  "Use a protocol to make switching to different implementation a bit easier
    We are currently using aws sdk, but only because aws-api currently blows our heap
    by loading entire objects into RAM.

   implement specific to our use case:
   - always cotained to a single bucket
   - public-read acl
   - only expose data we care about
   - put and get at granularity of file only (no streams or strings, etc)"
  (list-objects [object-store])
  (put-object [object-store object-key from-file])
  (get-object [object-store object-key to-file])
  (delete-object [object-store object-key])
  (copy-object [object-store source-key dest-key]))

(defrecord AwsSdkObjectStore [^S3Client s3 opts]
  IObjectStore
  (list-objects [_]
    (let [{:keys [bucket-name]} opts
          ^ListObjectsV2Request request (-> (ListObjectsV2Request/builder)
                                            (.bucket bucket-name)
                                            .build)]
      (->> (.listObjectsV2 s3 request)
           .contents
           (mapv (fn [^S3Object o] {:key (.key o)})))))
  (put-object [_ object-key from-file]
    (let [{:keys [bucket-name]} opts
          ^PutObjectRequest request (-> (PutObjectRequest/builder)
                                        (.bucket bucket-name)
                                        (.key object-key)
                                        (.acl ObjectCannedACL/PUBLIC_READ)
                                        .build)]
      (.putObject s3 request (RequestBody/fromFile (io/file from-file)))))
  (get-object [_ object-key to-file]
    (let [{:keys [bucket-name]} opts
          ^GetObjectRequest request (-> (GetObjectRequest/builder)
                                        (.bucket bucket-name)
                                        (.key object-key)
                                        .build)]
      (.getObject s3 request (ResponseTransformer/toFile (io/file to-file)))))
  (delete-object [_ object-key]
    (let [{:keys [bucket-name]} opts
          ^DeleteObjectRequest request (-> (DeleteObjectRequest/builder)
                                           (.bucket bucket-name)
                                           (.key object-key)
                                           .build)]
      (.deleteObject s3 request)))
  (copy-object [_  source-key dest-key]
    (let [{:keys [bucket-name]} opts
          ^CopyObjectRequest request (-> (CopyObjectRequest/builder)
                                         (.sourceBucket bucket-name)
                                         (.sourceKey source-key)
                                         (.destinationBucket bucket-name)
                                         (.destinationKey dest-key)
                                         .build)]
      (.copyObject s3 request)))
  AutoCloseable
  (close [_] (.close s3)))

(defn s3-exo-client [{:keys [bucket-key bucket-secret bucket-region]}]
  (let [endpoint (format "https://sos-%s.exo.io" bucket-region)
        ^AwsCredentialsProvider creds-provider (StaticCredentialsProvider/create
                                                (AwsBasicCredentials/create bucket-key bucket-secret))]
    (.build (doto (S3Client/builder)
              (.region Region/AWS_GLOBAL) ;; AWS SDK requires this even though we are not using AWS services
              (.endpointOverride (java.net.URI. endpoint))
              (.credentialsProvider creds-provider)))))

(defn make-exo-object-store [opts]
  (let [s3 (s3-exo-client opts)]
    (AwsSdkObjectStore. s3 opts)))

(comment
  (require '[cljdoc.config :as cfg])

  ;; assumes you've loaded up secrets to a working exo endpoint
  (def opts (cfg/db-backup (cfg/config)))

  (:bucket-region opts)

  (:bucket-name opts)

  (spit "target/dummy-file.txt" "foobar")

  (def object-store (make-exo-object-store opts))

  (list-objects object-store)
  ;; => [{:key "daily/cljdoc-db-2024-09-03_2024-09-03T20-22-00.tar.zst"}
  ;;     {:key "daily/cljdoc-db-2024-09-17_2024-09-17T18-01-44.tar.zst"}]

  (put-object object-store "daily/dummy-file" "target/dummy-file.txt")
  ;; => #object[software.amazon.awssdk.services.s3.model.PutObjectResponse 0x67c79dcd "PutObjectResponse(ETag=\"3858f62230ac3c915f300c664312c63f\")"]

  (list-objects object-store)
  ;; => [{:key "daily/cljdoc-db-2024-09-03_2024-09-03T20-22-00.tar.zst"}
  ;;     {:key "daily/cljdoc-db-2024-09-17_2024-09-17T18-01-44.tar.zst"}
  ;;     {:key "daily/dummy-file"}]

  (get-object object-store "daily/dummy-file" "target/dummy-file.down.txt")
  ;; => #object[software.amazon.awssdk.services.s3.model.GetObjectResponse 0x7e2790ce "GetObjectResponse(AcceptRanges=bytes, LastModified=2024-09-21T14:12:04Z, ContentLength=6, ETag=\"3858f62230ac3c915f300c664312c63f\", ContentType=text/plain, Metadata={})"]

  (slurp "target/dummy-file.down.txt")
  ;; => "foobar"

  (delete-object object-store "daily/dummy-file")
  ;; => #object[software.amazon.awssdk.services.s3.model.DeleteObjectResponse 0x4465db91 "DeleteObjectResponse()"]

  (list-objects object-store)
  ;; => [{:key "daily/cljdoc-db-2024-09-03_2024-09-03T20-22-00.tar.zst"}
  ;;     {:key "daily/cljdoc-db-2024-09-17_2024-09-17T18-01-44.tar.zst"}]

  (put-object object-store "daily/dummy-file" "target/dummy-file.txt")
  ;; => #object[software.amazon.awssdk.services.s3.model.PutObjectResponse 0x517e713b "PutObjectResponse(ETag=\"3858f62230ac3c915f300c664312c63f\")"]

  (copy-object object-store "daily/dummy-file" "daily/dummy-file-copy")
  ;; => #object[software.amazon.awssdk.services.s3.model.CopyObjectResponse 0x4488e7e1 "CopyObjectResponse(CopyObjectResult=CopyObjectResult(ETag=3858f62230ac3c915f300c664312c63f, LastModified=2024-09-21T14:17:12.018Z))"]

  (list-objects object-store)
  ;; => [{:key "daily/cljdoc-db-2024-09-03_2024-09-03T20-22-00.tar.zst"}
  ;;     {:key "daily/cljdoc-db-2024-09-17_2024-09-17T18-01-44.tar.zst"}
  ;;     {:key "daily/dummy-file"}
  ;;     {:key "daily/dummy-file-copy"}]

  (get-object object-store "daily/dummy-file-copy" "target/dummy-file-copy.down.txt")
  ;; => #object[software.amazon.awssdk.services.s3.model.GetObjectResponse 0x437659bf "GetObjectResponse(AcceptRanges=bytes, LastModified=2024-09-21T14:17:12Z, ContentLength=6, ETag=\"3858f62230ac3c915f300c664312c63f\", ContentType=text/plain, Metadata={})"]

  (slurp "target/dummy-file-copy.down.txt")
  ;; => "foobar"

  (delete-object object-store "daily/dummy-file-copy")
  ;; => #object[software.amazon.awssdk.services.s3.model.DeleteObjectResponse 0x7235fc92 "DeleteObjectResponse()"]

  (delete-object object-store "daily/dummy-file")
  ;; => #object[software.amazon.awssdk.services.s3.model.DeleteObjectResponse 0x2507c9f0 "DeleteObjectResponse()"]

  (list-objects object-store)
  ;; => [{:key "daily/cljdoc-db-2024-09-03_2024-09-03T20-22-00.tar.zst"}
  ;;     {:key "daily/cljdoc-db-2024-09-17_2024-09-17T18-01-44.tar.zst"}]

  (.close object-store)

  (list-objects object-store)
  ;; => Execution error (IllegalStateException) at org.apache.http.util.Asserts/check (Asserts.java:34).
  ;;    Connection pool shut down

  :eoc)
