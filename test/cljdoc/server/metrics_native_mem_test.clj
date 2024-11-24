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
            {:reserved {:kb 2724942},
             :committed {:kb 1497650},
             :malloc {:kb 130882, :cnt 951709, :peak {:kb 142074, :cnt 947473}},
             :mmap {:reserved {:kb 2594060}, :committed {:kb 1366768}}}}
           {:java-heap
            {:reserved {:kb 1048576},
             :committed {:kb 1048576},
             :mmap
             {:reserved {:kb 1048576}, :committed {:kb 1048576, :at-peak true}}}}
           {:class
            {:reserved {:kb 1072866},
             :committed {:kb 71906},
             :classes
             {:cnt 39698,
              :instance-classes {:cnt 38579},
              :array-classes {:cnt 1119}},
             :malloc {:kb 24290, :cnt 377958, :at-peak true},
             :mmap
             {:reserved {:kb 1048576},
              :committed {:kb 47616, :at-peak true},
              :metadata
              {:reserved {:kb 196608},
               :committed {:kb 169664},
               :used {:kb 146997},
               :waste {:kb 22667}},
              :class-space
              {:reserved {:kb 1048576},
               :committed {:kb 47616},
               :used {:kb 36517},
               :waste {:kb 11099}}}}}
           {:thread
            {:reserved {:kb 32872},
             :committed {:kb 3848},
             :threads {:cnt 32},
             :stack
             {:reserved {:kb 32768}, :committed {:kb 3744, :peak {:kb 3744}}},
             :malloc {:kb 69, :cnt 198, :peak {:kb 95, :cnt 251}},
             :arena {:kb 36, :cnt 62, :peak {:kb 1313, :cnt 60}}}}
           {:code
            {:reserved {:kb 287847},
             :committed {:kb 119951},
             :malloc {:kb 40159, :cnt 96615, :at-peak true},
             :mmap
             {:reserved {:kb 247688}, :committed {:kb 79792, :at-peak true}},
             :arena {:kb 0, :cnt 0, :peak {:kb 34, :cnt 2}}}}
           {:gc
            {:reserved {:kb 3438},
             :committed {:kb 3438},
             :malloc {:kb 22, :cnt 58, :peak {:kb 752, :cnt 243}},
             :mmap {:reserved {:kb 3416}, :committed {:kb 3416, :at-peak true}}}}
           {:compiler
            {:reserved {:kb 402},
             :committed {:kb 402},
             :malloc {:kb 238, :cnt 250, :peak {:kb 308, :cnt 276}},
             :arena {:kb 164, :cnt 4, :peak {:kb 38881, :cnt 13}}}}
           {:internal
            {:reserved {:kb 1478},
             :committed {:kb 1478},
             :malloc {:kb 1442, :cnt 52086, :at-peak true},
             :mmap {:reserved {:kb 36}, :committed {:kb 36, :at-peak true}}}}
           {:other
            {:reserved {:kb 225},
             :committed {:kb 225},
             :malloc {:kb 225, :cnt 42, :peak {:kb 230, :cnt 43}}}}
           {:symbol
            {:reserved {:kb 30464},
             :committed {:kb 30464},
             :malloc {:kb 26412, :cnt 346926, :at-peak true},
             :arena {:kb 4052, :cnt 1, :at-peak true}}}
           {:native-memory-tracking
            {:reserved {:kb 15115},
             :committed {:kb 15115},
             :malloc {:kb 245, :cnt 4449, :at-peak true},
             :tracking-overhead {:kb 14870}}}
           {:shared-class-space
            {:reserved {:kb 16384},
             :committed {:kb 13916},
             :readonly {:kb 0},
             :mmap
             {:reserved {:kb 16384}, :committed {:kb 13916, :peak {:kb 14100}}}}}
           {:arena-chunk
            {:reserved {:kb 1745},
             :committed {:kb 1745},
             :malloc {:kb 1745, :cnt 247, :peak {:kb 43026, :cnt 1114}}}}
           {:tracing
            {:reserved {:kb 32},
             :committed {:kb 32},
             :arena {:kb 32, :cnt 1, :at-peak true}}}
           {:module
            {:reserved {:kb 10688},
             :committed {:kb 10688},
             :malloc {:kb 10688, :cnt 45253, :at-peak true}}}
           {:safepoint
            {:reserved {:kb 8},
             :committed {:kb 8},
             :mmap {:reserved {:kb 8}, :committed {:kb 8, :at-peak true}}}}
           {:synchronization
            {:reserved {:kb 2255},
             :committed {:kb 2255},
             :malloc {:kb 2255, :cnt 22186, :at-peak true}}}
           {:serviceability
            {:reserved {:kb 18},
             :committed {:kb 18},
             :malloc {:kb 18, :cnt 28, :at-peak true}}}
           {:metaspace
            {:reserved {:kb 200528},
             :committed {:kb 173584},
             :malloc {:kb 3920, :cnt 5390, :at-peak true},
             :mmap
             {:reserved {:kb 196608}, :committed {:kb 169664, :at-peak true}}}}
           {:string-deduplication
            {:reserved {:kb 1},
             :committed {:kb 1},
             :malloc {:kb 1, :cnt 8, :at-peak true}}}
           {:object-monitors
            {:reserved {:kb 0},
             :committed {:kb 0},
             :malloc {:kb 0, :cnt 2, :peak {:kb 1223, :cnt 6262}}}}
           {:unknown
            {:reserved {:kb 0},
             :committed {:kb 0},
             :mmap {:reserved {:kb 0}, :committed {:kb 0, :peak {:kb 20}}}}}])
         (mnm/parse-output-text "

Native Memory Tracking:

(Omitting categories weighting less than 1KB)

Total: reserved=2724942KB, committed=1497650KB
       malloc: 130882KB #951709, peak=142074KB #947473
       mmap:   reserved=2594060KB, committed=1366768KB

-                 Java Heap (reserved=1048576KB, committed=1048576KB)
                            (mmap: reserved=1048576KB, committed=1048576KB, at peak)

-                     Class (reserved=1072866KB, committed=71906KB)
                            (classes #39698)
                            (  instance classes #38579, array classes #1119)
                            (malloc=24290KB #377958) (at peak)
                            (mmap: reserved=1048576KB, committed=47616KB, at peak)
                            (  Metadata:   )
                            (    reserved=196608KB, committed=169664KB)
                            (    used=146997KB)
                            (    waste=22667KB =13.36%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=47616KB)
                            (    used=36517KB)
                            (    waste=11099KB =23.31%)

-                    Thread (reserved=32872KB, committed=3848KB)
                            (threads #32)
                            (stack: reserved=32768KB, committed=3744KB, peak=3744KB)
                            (malloc=69KB #198) (peak=95KB #251)
                            (arena=36KB #62) (peak=1313KB #60)

-                      Code (reserved=287847KB, committed=119951KB)
                            (malloc=40159KB #96615) (at peak)
                            (mmap: reserved=247688KB, committed=79792KB, at peak)
                            (arena=0KB #0) (peak=34KB #2)

-                        GC (reserved=3438KB, committed=3438KB)
                            (malloc=22KB #58) (peak=752KB #243)
                            (mmap: reserved=3416KB, committed=3416KB, at peak)

-                  Compiler (reserved=402KB, committed=402KB)
                            (malloc=238KB #250) (peak=308KB #276)
                            (arena=164KB #4) (peak=38881KB #13)

-                  Internal (reserved=1478KB, committed=1478KB)
                            (malloc=1442KB #52086) (at peak)
                            (mmap: reserved=36KB, committed=36KB, at peak)

-                     Other (reserved=225KB, committed=225KB)
                            (malloc=225KB #42) (peak=230KB #43)

-                    Symbol (reserved=30464KB, committed=30464KB)
                            (malloc=26412KB #346926) (at peak)
                            (arena=4052KB #1) (at peak)

-    Native Memory Tracking (reserved=15115KB, committed=15115KB)
                            (malloc=245KB #4449) (at peak)
                            (tracking overhead=14870KB)

-        Shared class space (reserved=16384KB, committed=13916KB, readonly=0KB)
                            (mmap: reserved=16384KB, committed=13916KB, peak=14100KB)

-               Arena Chunk (reserved=1745KB, committed=1745KB)
                            (malloc=1745KB #247) (peak=43026KB #1114)

-                   Tracing (reserved=32KB, committed=32KB)
                            (arena=32KB #1) (at peak)

-                    Module (reserved=10688KB, committed=10688KB)
                            (malloc=10688KB #45253) (at peak)

-                 Safepoint (reserved=8KB, committed=8KB)
                            (mmap: reserved=8KB, committed=8KB, at peak)

-           Synchronization (reserved=2255KB, committed=2255KB)
                            (malloc=2255KB #22186) (at peak)

-            Serviceability (reserved=18KB, committed=18KB)
                            (malloc=18KB #28) (at peak)

-                 Metaspace (reserved=200528KB, committed=173584KB)
                            (malloc=3920KB #5390) (at peak)
                            (mmap: reserved=196608KB, committed=169664KB, at peak)

-      String Deduplication (reserved=1KB, committed=1KB)
                            (malloc=1KB #8) (at peak)

-           Object Monitors (reserved=0KB, committed=0KB)
                            (malloc=0KB #2) (peak=1223KB #6262)

-                   Unknown (reserved=0KB, committed=0KB)
                            (mmap: reserved=0KB, committed=0KB, peak=20KB)
"))))
