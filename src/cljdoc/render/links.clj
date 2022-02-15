(ns cljdoc.render.links)

(defn github-url [type]
  (let [base "https://github.com/cljdoc/cljdoc"]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :roadmap            (str base "/blob/master/doc/roadmap.adoc")
      :running-locally    (str base "/blob/master/doc/running-cljdoc-locally.adoc")
      :userguide/scm-faq  (str base "/blob/master/doc/userguide/faq.md#how-do-i-set-scm-info-for-my-project")
      :userguide/authors  (str base "/blob/master/doc/userguide/for-library-authors.adoc")
      :userguide/users    (str base "/blob/master/doc/userguide/for-users.md")

      :userguide/basic-setup  (str (github-url :userguide/authors) "#basic-setup")
      :userguide/articles     (str (github-url :userguide/authors) "#articles")
      :userguide/offline-docs (str (github-url :userguide/users) "#offline-docs"))))
