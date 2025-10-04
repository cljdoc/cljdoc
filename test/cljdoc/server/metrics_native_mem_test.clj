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
             {:reserved {:kb 2003}, :committed {:kb 2004, :peak {:kb 2005}}}}}
           {:class
            {:reserved {:kb 3001},
             :committed {:kb 3002},
             :classes
             {:cnt 3003,
              :instance-classes {:cnt 3004},
              :array-classes {:cnt 3005}},
             :malloc {:kb 3006, :cnt 3007, :peak {:kb 3008 :cnt 3009}},
             :mmap
             {:reserved {:kb 3010},
              :committed {:kb 3011, :at-peak true},
              :metadata
              {:reserved {:kb 3012},
               :committed {:kb 3013},
               :used {:kb 3014},
               :waste {:kb 3015}},
              :class-space
              {:reserved {:kb 3016},
               :committed {:kb 3017},
               :used {:kb 3018},
               :waste {:kb 3019}}}}}
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
             :malloc {:kb 5003, :cnt 5004, :peak {:kb 5005 :cnt 5006}},
             :mmap
             {:reserved {:kb 5007}, :committed {:kb 5008, :at-peak true}},
             :arena {:kb 5009, :cnt 5010, :peak {:kb 5011, :cnt 5012}}}}
           {:gc
            {:reserved {:kb 6001},
             :committed {:kb 6002},
             :malloc {:kb 6003, :cnt 6004, :peak {:kb 6005, :cnt 6006}},
             :mmap {:reserved {:kb 6007}, :committed {:kb 6008, :peak {:kb 6009}}}
             :arena {:kb 6010 :cnt 6011 :peak {:kb 6012 :cnt 6013}}}}
           {:gccardset
            {:reserved {:kb 6501},
             :committed {:kb 6502},
             :malloc {:kb 6503, :cnt 6504, :peak {:kb 6505, :cnt 6505}}}}
           {:compiler
            {:reserved {:kb 7001},
             :committed {:kb 7002},
             :malloc {:kb 7003, :cnt 7004, :peak {:kb 7005, :cnt 7006}},
             :arena {:kb 7007, :cnt 7008, :peak {:kb 7009, :cnt 7010}}}}
           {:internal
            {:reserved {:kb 8001},
             :committed {:kb 8002},
             :malloc {:kb 8003, :cnt 8004, :peak {:kb 8005 :cnt 8006}},
             :mmap {:reserved {:kb 8007}, :committed {:kb 8008, :at-peak true}}}}
           {:other
            {:reserved {:kb 9001},
             :committed {:kb 9002},
             :malloc {:kb 9003, :cnt 9004, :peak {:kb 9005, :cnt 9006}}}}
           {:symbol
            {:reserved {:kb 10001},
             :committed {:kb 10002},
             :malloc {:kb 10003, :cnt 10004, :peak {:kb 10005 :cnt 10006}},
             :arena {:kb 10007, :cnt 10008, :at-peak true}}}
           {:native-memory-tracking
            {:reserved {:kb 11001},
             :committed {:kb 11002},
             :malloc {:kb 11003, :cnt 11004, :peak {:kb 11005 :cnt 11006}},
             :tracking-overhead {:kb 11007}}}
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
             :malloc {:kb 14003 :cnt 14004 :peak {:kb 14005 :cnt 14006}}
             :arena {:kb 14007, :cnt 14008, :at-peak true}}}
           {:module
            {:reserved {:kb 15001},
             :committed {:kb 15002},
             :malloc {:kb 15003, :cnt 15004, :peak {:kb 15005 :cnt 15006}}}}
           {:safepoint
            {:reserved {:kb 16001},
             :committed {:kb 16002},
             :mmap {:reserved {:kb 16003}, :committed {:kb 16004, :at-peak true}}}}
           {:synchronization
            {:reserved {:kb 17001},
             :committed {:kb 17002},
             :malloc {:kb 17003, :cnt 17004, :peak {:kb 17005 :cnt 17006}}}}
           {:serviceability
            {:reserved {:kb 18001},
             :committed {:kb 18002},
             :malloc {:kb 18003, :cnt 18004, :peak {:kb 18005 :cnt 18006}}}}
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
             :malloc {:kb 21003, :cnt 21004 :peak {:kb 21005, :cnt 21006}}}}])
         ;; grabbed from real output, with numeric vals changed for testing
         (mnm/parse-output-text "

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=1001KB, committed=1002KB
       malloc: 1003KB #1004, peak=1005KB #1006
       mmap:   reserved=1007KB, committed=1008KB

-                 Java Heap (reserved=2001KB, committed=2002KB)
                            (mmap: reserved=2003KB, committed=2004KB, peak=2005KB)

-                     Class (reserved=3001KB, committed=3002KB)
                            (classes #3003)
                            (  instance classes #3004, array classes #3005)
                            (malloc=3006KB tag=Class #3007) (peak=3008KB #3009)
                            (mmap: reserved=3010KB, committed=3011KB, at peak)
                            (  Metadata:   )
                            (    reserved=3012KB, committed=3013KB)
                            (    used=3014KB)
                            (    waste=3015KB =12.18%)
                            (  Class space:)
                            (    reserved=3016KB, committed=3017KB)
                            (    used=3018KB)
                            (    waste=3019KB =19.86%)

-                    Thread (reserved=4001KB, committed=4002KB)
                            (threads #4003)
                            (stack: reserved=4004KB, committed=4005KB, peak=4006KB)
                            (malloc=4007KB tag=Thread #4008) (peak=4009KB #4010)
                            (arena=4011KB #4012) (peak=4013KB #4014)

-                      Code (reserved=5001KB, committed=5002KB)
                            (malloc=5003KB tag=Code #5004) (peak=5005KB #5006)
                            (mmap: reserved=5007KB, committed=5008KB, at peak)
                            (arena=5009KB #5010) (peak=5011KB #5012)

-                        GC (reserved=6001KB, committed=6002KB)
                            (malloc=6003KB tag=GC #6004) (peak=6005KB #6006)
                            (mmap: reserved=6007KB, committed=6008KB, peak=6009KB)
                            (arena=6010KB #6011) (peak=6012KB #6013)

-                 GCCardSet (reserved=6501KB, committed=6502KB)
                            (malloc=6503KB tag=GCCardSet #6504) (peak=6505KB #6505)

-                  Compiler (reserved=7001KB, committed=7002KB)
                            (malloc=7003KB tag=Compiler #7004) (peak=7005KB #7006)
                            (arena=7007KB #7008) (peak=7009KB #7010)

-                  Internal (reserved=8001KB, committed=8002KB)
                            (malloc=8003KB tag=Internal #8004) (peak=8005KB #8006)
                            (mmap: reserved=8007KB, committed=8008KB, at peak)

-                     Other (reserved=9001KB, committed=9002KB)
                            (malloc=9003KB tag=Other #9004) (peak=9005KB #9006)

-                    Symbol (reserved=10001KB, committed=10002KB)
                            (malloc=10003KB tag=Symbol #10004) (peak=10005KB #10006)
                            (arena=10007KB #10008) (at peak)

-    Native Memory Tracking (reserved=11001KB, committed=11002KB)
                            (malloc=11003KB tag=Native Memory Tracking #11004) (peak=11005KB #11006)
                            (tracking overhead=11007KB)

-        Shared class space (reserved=12001KB, committed=12002KB, readonly=12003KB)
                            (mmap: reserved=12004KB, committed=12005KB, peak=12006KB)

-               Arena Chunk (reserved=13001KB, committed=13002KB)
                            (malloc=13003KB tag=Arena Chunk #13004) (peak=13005KB #13006)

-                   Tracing (reserved=14001KB, committed=14002KB)
                            (malloc=14003KB tag=Tracing #14004) (peak=14005KB #14006)
                            (arena=14007KB #14008) (at peak)

-                    Module (reserved=15001KB, committed=15002KB)
                            (malloc=15003KB tag=Module #15004) (peak=15005KB #15006)

-                 Safepoint (reserved=16001KB, committed=16002KB)
                            (mmap: reserved=16003KB, committed=16004KB, at peak)

-           Synchronization (reserved=17001KB, committed=17002KB)
                            (malloc=17003KB tag=Synchronization #17004) (peak=17005KB #17006)

-            Serviceability (reserved=18001KB, committed=18002KB)
                            (malloc=18003KB tag=Serviceability #18004) (peak=18005KB #18006)

-                 Metaspace (reserved=19001KB, committed=19002KB)
                            (malloc=19003KB tag=Metaspace #19004) (at peak)
                            (mmap: reserved=19005KB, committed=19006KB, at peak)

-      String Deduplication (reserved=20001KB, committed=20002KB)
                            (malloc=20003KB tag=String Deduplication #20004) (at peak)

-           Object Monitors (reserved=21001KB, committed=21002KB)
                            (malloc=21003KB tag=Object Monitors #21004) (peak=21005KB #21006)
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
               (    beehive tag=I am a scallywag tag who should be ignored #76)
               (six tricks 20kb)"))))
