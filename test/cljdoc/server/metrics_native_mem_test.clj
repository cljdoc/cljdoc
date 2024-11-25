(ns cljdoc.server.metrics-native-mem-test
  (:require [cljdoc.server.metrics-native-mem :as mnm]
            [clojure.test :as t]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]))

(t/deftest native-memory-tracking-disabled-test
  (doseq [output ["Native memory tracking is not enabled"
                  "\nNative memory tracking is not enabled"
                  "foobar"
                  ""]]
    (t/is (match?
           (m/nested-equals [{}])
           (mnm/parse-output-text output)))))

(t/deftest sanity-parse-test
  (t/is (match?
         (m/nested-equals
          [{:total
            {:reserved {:kb 1001},
             :committed {:kb 1002},
             :malloc {:kb 1003, :cnt 1004, :peak {:kb 1005, :cnt 1006}},
             :mmap {:reserved {:kb 1007}, :committed {:kb 1008}}}}
           {:java-heap
            {:reserved {:kb 2001},
             :committed {:kb 2002},
             :mmap
             {:reserved {:kb 2003}, :committed {:kb 2004, :at-peak true}}}}
           {:class
            {:reserved {:kb 3001},
             :committed {:kb 3002},
             :classes
             {:cnt 3003,
              :instance-classes {:cnt 3004},
              :array-classes {:cnt 3005}},
             :malloc {:kb 3006, :cnt 3007, :at-peak true},
             :mmap
             {:reserved {:kb 3008},
              :committed {:kb 3009, :at-peak true},
              :metadata
              {:reserved {:kb 3010},
               :committed {:kb 3011},
               :used {:kb 3012},
               :waste {:kb 3013}},
              :class-space
              {:reserved {:kb 3014},
               :committed {:kb 3015},
               :used {:kb 3016},
               :waste {:kb 3017}}}}}
           {:thread
            {:reserved {:kb 4001},
             :committed {:kb 4002},
             :threads {:cnt 4003},
             :stack
             {:reserved {:kb 4004}, :committed {:kb 4005, :peak {:kb 4006}}},
             :malloc {:kb 4007, :cnt 4008, :peak {:kb 4009 :cnt 4010}},
             :arena {:kb 4011, :cnt 4012, :peak {:kb 4013, :cnt 4014}}}}
           {:code
            {:reserved {:kb 5001},
             :committed {:kb 5002},
             :malloc {:kb 5003, :cnt 5004, :at-peak true},
             :mmap
             {:reserved {:kb 5005}, :committed {:kb 5006, :at-peak true}},
             :arena {:kb 5007, :cnt 5008, :peak {:kb 5009, :cnt 5010}}}}
           {:gc
            {:reserved {:kb 6001},
             :committed {:kb 6002},
             :malloc {:kb 6003, :cnt 6004, :peak {:kb 6005, :cnt 6006}},
             :mmap {:reserved {:kb 6007}, :committed {:kb 6008, :at-peak true}}}}
           {:compiler
            {:reserved {:kb 7001},
             :committed {:kb 7002},
             :malloc {:kb 7003, :cnt 7004, :peak {:kb 7005, :cnt 7006}},
             :arena {:kb 7007, :cnt 7008, :peak {:kb 7009, :cnt 7010}}}}
           {:internal
            {:reserved {:kb 8001},
             :committed {:kb 8002},
             :malloc {:kb 8003, :cnt 8004, :at-peak true},
             :mmap {:reserved {:kb 8005}, :committed {:kb 8006, :at-peak true}}}}
           {:other
            {:reserved {:kb 9001},
             :committed {:kb 9002},
             :malloc {:kb 9003, :cnt 9004, :peak {:kb 9005, :cnt 9006}}}}
           {:symbol
            {:reserved {:kb 10001},
             :committed {:kb 10002},
             :malloc {:kb 10003, :cnt 10004, :at-peak true},
             :arena {:kb 10005, :cnt 10006, :at-peak true}}}
           {:native-memory-tracking
            {:reserved {:kb 11001},
             :committed {:kb 11002},
             :malloc {:kb 11003, :cnt 11004, :at-peak true},
             :tracking-overhead {:kb 11005}}}
           {:shared-class-space
            {:reserved {:kb 12001},
             :committed {:kb 12002},
             :readonly {:kb 12003},
             :mmap
             {:reserved {:kb 12004}, :committed {:kb 12005, :peak {:kb 12006}}}}}
           {:arena-chunk
            {:reserved {:kb 13001},
             :committed {:kb 13002},
             :malloc {:kb 13003, :cnt 13004, :peak {:kb 13005, :cnt 13006}}}}
           {:tracing
            {:reserved {:kb 14001},
             :committed {:kb 14002},
             :arena {:kb 14003, :cnt 14004, :at-peak true}}}
           {:module
            {:reserved {:kb 15001},
             :committed {:kb 15002},
             :malloc {:kb 15003, :cnt 15004, :at-peak true}}}
           {:safepoint
            {:reserved {:kb 16001},
             :committed {:kb 16002},
             :mmap {:reserved {:kb 16003}, :committed {:kb 16004, :at-peak true}}}}
           {:synchronization
            {:reserved {:kb 17001},
             :committed {:kb 17002},
             :malloc {:kb 17003, :cnt 17004, :at-peak true}}}
           {:serviceability
            {:reserved {:kb 18001},
             :committed {:kb 18002},
             :malloc {:kb 18003, :cnt 18004, :at-peak true}}}
           {:metaspace
            {:reserved {:kb 19001},
             :committed {:kb 19002},
             :malloc {:kb 19003, :cnt 19004, :at-peak true},
             :mmap
             {:reserved {:kb 19005}, :committed {:kb 19006, :at-peak true}}}}
           {:string-deduplication
            {:reserved {:kb 20001},
             :committed {:kb 20002},
             :malloc {:kb 20003, :cnt 20004, :at-peak true}}}
           {:object-monitors
            {:reserved {:kb 21001},
             :committed {:kb 21002},
             :malloc {:kb 21003, :cnt 21004 :peak {:kb 21005, :cnt 21006}}}}
           {:unknown
            {:reserved {:kb 22001},
             :committed {:kb 22002},
             :mmap {:reserved {:kb 22003}, :committed {:kb 22004, :peak {:kb 22005}}}}}])
         ;; grabbed from real output, with numeric vals changed for testing
         (mnm/parse-output-text "

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=1001KB, committed=1002KB
       malloc: 1003KB #1004, peak=1005KB #1006
       mmap:   reserved=1007KB, committed=1008KB

-                 Java Heap (reserved=2001KB, committed=2002KB)
                            (mmap: reserved=2003KB, committed=2004KB, at peak)

-                     Class (reserved=3001KB, committed=3002KB)
                            (classes #3003)
                            (  instance classes #3004, array classes #3005)
                            (malloc=3006KB #3007) (at peak)
                            (mmap: reserved=3008KB, committed=3009KB, at peak)
                            (  Metadata:   )
                            (    reserved=3010KB, committed=3011KB)
                            (    used=3012KB)
                            (    waste=3013KB =13.36%)
                            (  Class space:)
                            (    reserved=3014KB, committed=3015KB)
                            (    used=3016KB)
                            (    waste=3017KB =23.31%)

-                    Thread (reserved=4001KB, committed=4002KB)
                            (threads #4003)
                            (stack: reserved=4004KB, committed=4005KB, peak=4006KB)
                            (malloc=4007KB #4008) (peak=4009KB #4010)
                            (arena=4011KB #4012) (peak=4013KB #4014)

-                      Code (reserved=5001KB, committed=5002KB)
                            (malloc=5003KB #5004) (at peak)
                            (mmap: reserved=5005KB, committed=5006KB, at peak)
                            (arena=5007KB #5008) (peak=5009KB #5010)

-                        GC (reserved=6001KB, committed=6002KB)
                            (malloc=6003KB #6004) (peak=6005KB #6006)
                            (mmap: reserved=6007KB, committed=6008KB, at peak)

-                  Compiler (reserved=7001KB, committed=7002KB)
                            (malloc=7003KB #7004) (peak=7005KB #7006)
                            (arena=7007KB #7008) (peak=7009KB #7010)

-                  Internal (reserved=8001KB, committed=8002KB)
                            (malloc=8003KB #8004) (at peak)
                            (mmap: reserved=8005KB, committed=8006KB, at peak)

-                     Other (reserved=9001KB, committed=9002KB)
                            (malloc=9003KB #9004) (peak=9005KB #9006)

-                    Symbol (reserved=10001KB, committed=10002KB)
                            (malloc=10003KB #10004) (at peak)
                            (arena=10005KB #10006) (at peak)

-    Native Memory Tracking (reserved=11001KB, committed=11002KB)
                            (malloc=11003KB #11004) (at peak)
                            (tracking overhead=11005KB)

-        Shared class space (reserved=12001KB, committed=12002KB, readonly=12003KB)
                            (mmap: reserved=12004KB, committed=12005KB, peak=12006KB)

-               Arena Chunk (reserved=13001KB, committed=13002KB)
                            (malloc=13003KB #13004) (peak=13005KB #13006)

-                   Tracing (reserved=14001KB, committed=14002KB)
                            (arena=14003KB #14004) (at peak)

-                    Module (reserved=15001KB, committed=15002KB)
                            (malloc=15003KB #15004) (at peak)

-                 Safepoint (reserved=16001KB, committed=16002KB)
                            (mmap: reserved=16003KB, committed=16004KB, at peak)

-           Synchronization (reserved=17001KB, committed=17002KB)
                            (malloc=17003KB #17004) (at peak)

-            Serviceability (reserved=18001KB, committed=18002KB)
                            (malloc=18003KB #18004) (at peak)

-                 Metaspace (reserved=19001KB, committed=19002KB)
                            (malloc=19003KB #19004) (at peak)
                            (mmap: reserved=19005KB, committed=19006KB, at peak)

-      String Deduplication (reserved=20001KB, committed=20002KB)
                            (malloc=20003KB #20004) (at peak)

-           Object Monitors (reserved=21001KB, committed=21002KB)
                            (malloc=21003KB #21004) (peak=21005KB #21006)

-                   Unknown (reserved=22001KB, committed=22002KB)
                            (mmap: reserved=22003KB, committed=22004KB, peak=22005KB)
"))))

(t/deftest hypothetical-parse-test
  ;; Our parsing tries to be generic to support potential new entries
  (t/is (match?
         (m/nested-equals [{:total
                            {:big {:kb 123456789012345 :cnt 234567890123456}
                             :zero {:kb 0 :cnt 0}}}
                           {:metrics-value-order
                            {:bar {:cnt 443}
                             :foo {:cnt 32 :kb 99 :at-peak true}
                             :abc {:cnt 11 :kb 23}}}
                           {:hierarchy
                            {:one {:cnt 32}
                             :two {:buckle
                                   {:my-big
                                    {:shoe
                                     {:cnt 44}}}
                                   :three
                                   {:four
                                    {:kb 5}}
                                   :five
                                   {:beehive
                                    {:cnt 76}}}
                             :six-tricks {:kb 20}}}])
         (mnm/parse-output-text "The header lines can change,
we do not care,
we only expect interesting data to start with Total:

Total: big=123456789012345KB #234567890123456 zero=0KB #0

-    Metrics Value Order (bar=#443 foo #32 99KB at peak)
                         (abc 23KB #11)

-    Hierarchy (one: #32)
               ( two:)
               (  buckle:)
               (         my big:)
               (           shoe #44)
               (  three:)
               (    four: 5kb)
               (  five:)
               (    beehive #76)
               (six tricks 20kb)"))))
