(ns cljdoc.render.common)

(defn github-url [type]
  (let [base "https://github.com/martinklepsch/cljdoc"]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :userguide/articles (str base "/blob/master/doc/userguide/articles.md")
      :userguide/scm-faq  (str base "/blob/master/doc/userguide/faq.md#how-do-i-set-scm-info-for-my-project"))))
