(ns cljdoc.render.links)

(defn github-url [type]
  (let [org "cljdoc"
        branch "master"
        base (str "https://github.com/" org "/cljdoc")
        doc-base (str base "/blob/" branch "/doc/")]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :roadmap            (str doc-base "roadmap.adoc")
      :running-locally    (str doc-base "running-cljdoc-locally.adoc")
      :userguide/authors  (str doc-base "userguide/for-library-authors.adoc")
      :userguide/users    (str doc-base "userguide/for-users.md")

      :userguide/basic-setup  (str (github-url :userguide/authors) "#basic-setup")
      :userguide/scm-faq      (str (github-url :userguide/authors) "#git-sources")
      :userguide/articles     (str (github-url :userguide/authors) "#articles")
      :userguide/offline-docs (str (github-url :userguide/users) "#offline-docs"))))
