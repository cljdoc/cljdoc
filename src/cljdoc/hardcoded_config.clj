(ns cljdoc.hardcoded-config
  "This is a draft for what users may specify via a configuration file in
  their repository, e.g. `deps.edn`")

(def projects
  {"manifold" {:cljdoc.api/namespaces '[manifold.deferred manifold.stream manifold.time manifold.bus manifold.executor]
               :cljdoc.api/platforms #{"clj"}
               :cljdoc.doc/tree [["Readme" {:file "README.md"}]
                                 ["Rationale" {:file "docs/rationale.md"}]
                                 ["Deferreds" {:file "docs/deferred.md"}]
                                 ["Streams" {:file "docs/stream.md"}]
                                 ["Execution" {:file "docs/execution.md"}]]}
   "yada" {:cljdoc.doc/tree [["Readme" {:file "README.md"}]
                             ["Preface" {:file "doc/preface.adoc"}]
                             ["Basics" {}
                              ["Introduction" {:file "doc/intro.adoc"}]
                              ["Getting Started" {:file "doc/getting-started.adoc"}]
                              ["Hello World" {:file "doc/hello.adoc"}]
                              ["Installation" {:file "doc/install.adoc"}]
                              ["Resources" {:file "doc/resources.adoc"}]
                              ["Parameters" {:file "doc/parameters.adoc"}]
                              ["Properties" {:file "doc/properties.adoc"}]
                              ["Methods" {:file "doc/methods.adoc"}]
                              ["Representations" {:file "doc/representations.adoc"}]
                              ["Responses" {:file "doc/responses.adoc"}]
                              ["Security" {:file "doc/security.adoc"}]
                              ["Routing" {:file "doc/routing.adoc"}]
                              ["Phonebook" {:file "doc/phonebook.adoc"}]
                              ["Swagger" {:file "doc/swagger.adoc"}]]
                             ["Advanced Topics" {}
                              ["Async" {:file "doc/async.adoc"}]
                              ["Search Engine" {:file "doc/searchengine.adoc"}]
                              ["Server Sent Events" {:file "doc/sse.adoc"}]
                              ["Chat Server" {:file "doc/chatserver.adoc"}]
                              ["Handling Request Bodies" {:file "doc/requestbodies.adoc"}]
                              ["Selfie Uploader" {:file "doc/selfieuploader.adoc"}]
                              ["Handlers" {:file "doc/handlers.adoc"}]
                              ["Request Context" {:file "doc/requestcontext.adoc"}]
                              ["Interceptors" {:file "doc/interceptors.adoc"}]
                              ["Subresources" {:file "doc/subresources.adoc"}]
                              ["Fileserver" {:file "doc/fileserver.adoc"}]
                              ["Testing" {:file "doc/testing.adoc"}]]
                             ["Reference" {}
                              ["Glossary" {:file "doc/glossary.adoc"}]
                              ["Reference" {:file "doc/reference.adoc"}]
                              ["Colophon" {:file "doc/colophon.adoc"}]]]}})
