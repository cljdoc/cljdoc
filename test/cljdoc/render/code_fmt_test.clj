(ns cljdoc.render.code-fmt-test
  (:require
   [cljdoc.render.code-fmt :as subject]
   [clojure.string :as string]
   [clojure.test :refer [deftest is]]))

(deftest snippet-blanks
  (is (= "" (subject/snippet nil)))
  (is (= "" (subject/snippet "")))
  (is (= "" (subject/snippet "  ")))
  (is (= "" (subject/snippet " \r\t\n  "))))

(deftest snippet-basics
  (is (= "a" (subject/snippet "  a"))))

(deftest snippet-call-examples
  (is (= "(are we good)" (subject/snippet "(are we good)")))

  (is (= (string/join "\n" ["(are just-wondering-if-we"
                            "     {:keys [wrap long things properly]}"
                            "     {:keys [hoping that we do]})"])
         (subject/snippet "(are just-wondering-if-we {:keys [wrap long things properly]} {:keys [hoping that we do]})")))

  (is (= (string/join "\n" ["(so-do-we {:keys [handle the case where keyword destructuring is crazy long and"
                            "                  in itself would cause a wrap]})"])
         (subject/snippet "(so-do-we {:keys [handle the case where keyword destructuring is crazy long and in itself would cause a wrap]})"))))

(deftest snippet-error-map-example
  (is (= ["{:cause \"clj-http: status 500\""
          " :data {:body \"{\\\"message\\\":\\\"An internal server error occurred.\\\"}\""
          "        :headers {\"connection\" \"keep-alive\""
          "                  \"content-length\" \"48\""
          "                  \"content-type\" \"application/json\""
          "                  \"date\" \"Mon, 27 Sep 2021 08:42:51 GMT\""
          "                  \"server\" \"nginx\""
          "                  \"strict-transport-security\" \"max-age=15724800\""
          "                  \"x-request-id\" \"e4c31bab-ac17-4a80-a311-4a5d72e282f7\"}"
          "        :status 500}"
          " :trace"
          "   [[slingshot.support$stack_trace invoke \"support.clj\" 201]"
          "    [clj_http.lite.client$wrap_exceptions$fn__407 invoke \"client.clj\" 36]"
          "    [clj_http.lite.client$wrap_basic_auth$fn__475 invoke \"client.clj\" 177]"
          "    [clj_http.lite.client$wrap_accept$fn__448 invoke \"client.clj\" 131]"
          "    [clj_http.lite.client$wrap_accept_encoding$fn__454 invoke \"client.clj\" 145]"
          "    [clj_http.lite.client$wrap_content_type$fn__443 invoke \"client.clj\" 124]"
          "    [clj_http.lite.client$wrap_form_params$fn__492 invoke \"client.clj\" 202]"
          "    [clj_http.lite.client$wrap_method$fn__487 invoke \"client.clj\" 195]"
          "    [clj_http.lite.client$wrap_unknown_host$fn__502 invoke \"client.clj\" 218]"
          "    [clj_http.lite.client$post invokeStatic \"client.clj\" 281]"
          "    [clj_http.lite.client$post doInvoke \"client.clj\" 278]"
          "    [clojure.lang.RestFn invoke \"RestFn.java\" 423]"
          "    [cljdoc.analysis.service.CircleCI trigger_build \"service.clj\" 56]"
          "    [cljdoc.server.api$analyze_and_import_api_BANG_ invokeStatic \"api.clj\" 17]"
          "    [cljdoc.server.api$analyze_and_import_api_BANG_ invoke \"api.clj\" 10]"
          "    [cljdoc.server.api$kick_off_build_BANG_$fn__28064 invoke \"api.clj\" 78]"
          "    [clojure.core$binding_conveyor_fn$fn__5772 invoke \"core.clj\" 2034]"
          "    [clojure.lang.AFn call \"AFn.java\" 18]"
          "    [java.util.concurrent.FutureTask run \"FutureTask.java\" 266]"
          "    [java.util.concurrent.ThreadPoolExecutor runWorker \"ThreadPoolExecutor.java\""
          "     1149]"
          "    [java.util.concurrent.ThreadPoolExecutor$Worker run"
          "     \"ThreadPoolExecutor.java\" 624] [java.lang.Thread run \"Thread.java\" 748]]"
          " :via [{:at [slingshot.support$stack_trace invoke \"support.clj\" 201]"
          "        :data {:body \"{\\\"message\\\":\\\"An internal server error occurred.\\\"}\""
          "               :headers {\"connection\" \"keep-alive\""
          "                         \"content-length\" \"48\""
          "                         \"content-type\" \"application/json\""
          "                         \"date\" \"Mon, 27 Sep 2021 08:42:51 GMT\""
          "                         \"server\" \"nginx\""
          "                         \"strict-transport-security\" \"max-age=15724800\""
          "                         \"x-request-id\" \"e4c31bab-ac17-4a80-a311-4a5d72e282f7\"}"
          "               :status 500}"
          "        :message \"clj-http: status 500\""
          "        :type clojure.lang.ExceptionInfo}]}"]
         ;; split lines for better diffs on failure
         (string/split-lines
          (subject/snippet (str ;; grabbed from a sample error_map_info from production
                              ;; TODO: consistent ordering of maps?
                            '{:cause "clj-http: status 500"
                              :data {:body "{\"message\":\"An internal server error occurred.\"}",
                                     :headers {"connection" "keep-alive",
                                               "content-length" "48",
                                               "content-type" "application/json",
                                               "date" "Mon, 27 Sep 2021 08:42:51 GMT",
                                               "server" "nginx",
                                               "strict-transport-security" "max-age=15724800",
                                               "x-request-id" "e4c31bab-ac17-4a80-a311-4a5d72e282f7"},
                                     :status 500},
                              :trace
                              [[slingshot.support$stack_trace invoke "support.clj" 201]
                               [clj_http.lite.client$wrap_exceptions$fn__407 invoke "client.clj" 36]
                               [clj_http.lite.client$wrap_basic_auth$fn__475 invoke "client.clj" 177]
                               [clj_http.lite.client$wrap_accept$fn__448 invoke "client.clj" 131]
                               [clj_http.lite.client$wrap_accept_encoding$fn__454 invoke "client.clj" 145]
                               [clj_http.lite.client$wrap_content_type$fn__443 invoke "client.clj" 124]
                               [clj_http.lite.client$wrap_form_params$fn__492 invoke "client.clj" 202]
                               [clj_http.lite.client$wrap_method$fn__487 invoke "client.clj" 195]
                               [clj_http.lite.client$wrap_unknown_host$fn__502 invoke "client.clj" 218]
                               [clj_http.lite.client$post invokeStatic "client.clj" 281]
                               [clj_http.lite.client$post doInvoke "client.clj" 278]
                               [clojure.lang.RestFn invoke "RestFn.java" 423]
                               [cljdoc.analysis.service.CircleCI trigger_build "service.clj" 56]
                               [cljdoc.server.api$analyze_and_import_api_BANG_ invokeStatic "api.clj" 17]
                               [cljdoc.server.api$analyze_and_import_api_BANG_ invoke "api.clj" 10]
                               [cljdoc.server.api$kick_off_build_BANG_$fn__28064 invoke "api.clj" 78]
                               [clojure.core$binding_conveyor_fn$fn__5772 invoke "core.clj" 2034]
                               [clojure.lang.AFn call "AFn.java" 18]
                               [java.util.concurrent.FutureTask run "FutureTask.java" 266]
                               [java.util.concurrent.ThreadPoolExecutor runWorker "ThreadPoolExecutor.java"
                                1149]
                               [java.util.concurrent.ThreadPoolExecutor$Worker run
                                "ThreadPoolExecutor.java" 624] [java.lang.Thread run "Thread.java" 748]],
                              :via [{:at [slingshot.support$stack_trace invoke "support.clj" 201],
                                     :data {:body "{\"message\":\"An internal server error occurred.\"}",
                                            :headers {"connection" "keep-alive",
                                                      "content-length" "48",
                                                      "content-type" "application/json",
                                                      "date" "Mon, 27 Sep 2021 08:42:51 GMT",
                                                      "server" "nginx",
                                                      "strict-transport-security" "max-age=15724800",
                                                      "x-request-id" "e4c31bab-ac17-4a80-a311-4a5d72e282f7"},
                                            :status 500},
                                     :message "clj-http: status 500",
                                     :type clojure.lang.ExceptionInfo}]}))))))
