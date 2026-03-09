(ns cljdoc.util.fixref-test
  (:require [cljdoc.test-util.html :as html]
            [cljdoc.util.fixref :as fixref]
            [clojure.test :as t]
            [hiccup2.core :as h]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]))

(set! *warn-on-reflection* true)

(defn- hiccup->html [elems]
  (str (h/html (apply list elems))))

;; some reasonable defaults for our tests
(def fix-opts {:scm-file-path "doc/path.adoc"
               :scm {:commit "#SHA#"
                     :url "https://scm/user/project"}
               :uri-map {}
               :version-entity {:group-id "mygroupid"
                                :artifact-id "myartifactid"
                                :version "1.2.3"}})
(defn- fix
  "Little helper that deals in hiccup rather than html"
  [hiccup opts]
  (html/->hiccup
   (fixref/fix (hiccup->html hiccup) opts)))

(t/deftest scm-link-favors-git-tag-representing-version-over-commit-sha-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "https://scm/user/project/blob/v1.2.3/doc/doc.md" :rel "nofollow"} "my doc"]
      [:img {:src "https://scm/user/project/raw/v1.2.3/doc/images/one.png"}]])
    (fix [[:a {:href "doc.md"} "my doc"]
          [:img {:src "images/one.png"}]]
         (assoc-in fix-opts [:scm :tag :name] "v1.2.3")))))

(t/deftest ignores-rendered-wikilinks-which-can-occur-in-docstrings-test
  (let [input [[:a {:href "down/deeper/to/doc.adoc" :data-source "wikilink"} "relative link"]
               [:a {:href "../upone/norm1.adoc" :data-source "wikilink"} "norm1"]
               [:a {:href "../../norm2.adoc" :data-source "wikilink"} "norm2"]]]
    (t/is (match? (m/nested-equals input)
                  (fix input fix-opts)))))

(t/deftest ignores-absolute-image-refs-test
  (let [input [[:img {:src "https://svgworld.com/abs.svg"}]
               [:img {:src "https://cljdoc.org/some/path/absolute-cljdoc.png"}]
               [:img {:src "https://clojure.org/images/clojure-logo-120b.png"}]]]
    (t/is (match? (m/nested-equals input)
                  (fix input fix-opts)))))

(t/deftest ignores-anchor-links-test
  (let [input [[:a {:href "#anchor"} "anchor link"]]]
    (t/is (match? (m/nested-equals input)
                  (fix input fix-opts)))))

(t/deftest renders-error-ref-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "#!cljdoc-error!ref-must-be-root-relative!"} "link text"]
      [:img {:src "#!cljdoc-error!ref-must-be-root-relative!"}]])
    (fix [[:a {:href "rel/ref/here.md"} "link text"]
          [:img {:src "rel/ref/here.png"}]]
         (dissoc fix-opts :scm-file-path)))
   "when scm file path is unknown (as is case from docstrings) and a relative path is specified"))

(t/deftest external-links-include-nofollow-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "https://clojure.org" :rel "nofollow"} "absolute link elsewhere"]
      [:a {:href "http://unsecure.com" :rel "nofollow"} "absolutely insecure"]])
    (fix [[:a {:href "https://clojure.org"} "absolute link elsewhere"]
          [:a {:href "http://unsecure.com"} "absolutely insecure"]]
         fix-opts))))

(t/deftest cljdoc-absolute-links-convert-to-root-relative-links-test
  ;; this supports local testing
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "/some/path/here"} "absolute link to cljdoc"]])
    (fix [[:a {:href "https://cljdoc.org/some/path/here"} "absolute link to cljdoc"]]
         fix-opts))))

(t/deftest unknown-scm-links-when-relative-point-to-normalized-scm-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "https://scm/user/project/blob/#SHA#/doc/path/down/deeper/to/doc.adoc" :rel "nofollow"}
       "relative link"]
      [:a {:href "https://scm/user/project/blob/#SHA#/doc/upone/norm1.adoc" :rel "nofollow"}
       "norm1"]
      [:a {:href "https://scm/user/project/blob/#SHA#/norm2.adoc" :rel "nofollow"}
       "norm2"]
      [:a {:href "https://scm/user/project/blob/#SHA#/../../../norm2.adoc" :rel "nofollow"}
       "norm2"]])
    (fix [[:a {:href "down/deeper/to/doc.adoc"} "relative link"]
          [:a {:href "../upone/norm1.adoc"} "norm1"]
          [:a {:href "../../norm2.adoc"} "norm2"]
          [:a {:href "../../../../../norm2.adoc"} "norm2"]]
         (assoc fix-opts :scm-file-path "doc/path/doc.adoc")))))

(t/deftest unknown-scm-links-when-root-relative-will-point-to-normalized-scm-project-root-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "https://scm/user/project/blob/#SHA#/root/relative/doc.md" :rel "nofollow"}
       "root relative link1"]
      [:a {:href "https://scm/user/project/blob/#SHA#/root/a/d/doc.md" :rel "nofollow"}
       "root relative link2"]
      [:a {:href "https://scm/user/project/blob/#SHA#/doc.md", :rel "nofollow"}
       "root relative link3"]])
    (fix [[:a {:href "/root/relative/doc.md"} "root relative link1"]
          [:a {:href "/root/./././relative/../a/b/c/../../d/doc.md"} "root relative link2"]
          [:a {:href "/root/relative/../../../../../doc.md"} "root relative link3"]]
         fix-opts))))

(t/deftest sourcehut-scm-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "https://git.sr.ht/~user/project/tree/#SHA#/doc/norm1.adoc" :rel "nofollow"}
       "relative link"]])
    (fix [[:a {:href "norm1.adoc"} "relative link"]]
         (assoc-in fix-opts [:scm :url] "https://git.sr.ht/~user/project")))))

(t/deftest imported-article-links-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "slugged-doc"} "slug converted"]])
    (fix [[:a {:href "slug/conversion/slugtest.adoc"} "slug converted"]]
         (assoc fix-opts
                :scm-file-path "doc/path/my-doc.adoc"
                :uri-map {"doc/path/slug/conversion/slugtest.adoc" "slugged-doc"})))))

(t/deftest imported-articles-can-point-to-html-files-for-offline-docset-test
  (doseq [[target-path expected]
          [[""         [[:a {:href "doc/offline.html"} "offline doc"]]]
           ["doc"      [[:a {:href "offline.html"} "offline doc"]]]
           ["api"      [[:a {:href "../doc/offline.html"} "offline doc"]]]]]
    (t/is (match?
           (m/nested-equals expected)
           (fix [[:a {:href "mapped.adoc"} "offline doc"]]
                (assoc fix-opts
                       :scm-file-path "doc/path/my-doc.adoc"
                       :target-path   target-path
                       :uri-map       {"doc/path/mapped.adoc" "doc/offline.html"})))
          (str "target-path: " (pr-str target-path)))))

(t/deftest scm-image-when-relative-points-to-normalized-scm-raw-ref-test
  (t/is
   (match?
    (m/nested-equals
     [[:img {:src "https://scm/user/project/raw/#SHA#/doc/path/rel1.png"}]
      [:img {:src "https://scm/user/project/raw/#SHA#/images/rel2.png"}]
      [:img {:src "https://scm/user/project/raw/#SHA#/homages/rel3.png"}]
      [:img {:src "https://scm/user/project/raw/#SHA#/../../../../../../../../rel4.png"}]])
    (fix [[:img {:src "rel1.png"}]
          [:img {:src "../../images/rel2.png"}]
          [:img {:src "../../images/../homages/./././rel3.png"}]
          [:img {:src "../../../../../../../../../../rel4.png"}]]
         (assoc fix-opts :scm-file-path "doc/path/doc.adoc")))))

(t/deftest scm-image-when-root-relative-points-to-scm-raw-ref-test
  (t/is
   (match?
    (m/nested-equals
     [[:img {:src "https://scm/user/project/raw/#SHA#/root/relative/image.png"}]
      [:img {:src "https://scm/user/project/raw/#SHA#/root/relative/.././image.png"}]])
    (fix [[:img {:src "/root/relative/image.png"}]
          [:img {:src "/root/relative/.././image.png"}]]
         (assoc fix-opts :scm-file-path "doc/path/doc.adoc")))))

(t/deftest scm-image-can-be-svg-test
  (t/is
   (match?
    (m/nested-equals
     [[:img {:src "https://scm/user/project/raw/#SHA#/doc/path/rel.svg"}]])
    (fix [[:img {:src "rel.svg"}]]
         (assoc fix-opts :scm-file-path "doc/path/doc.adoc")))))

(t/deftest scm-image-svg-sanitized-when-from-github-test
  (t/is
   (match?
    (m/nested-equals
     [[:img {:src "https://github.com/user/project/raw/#SHA#/doc/path/rel.svg?sanitize=true"}]])
    (fix [[:img {:src "rel.svg"}]]
         {:scm-file-path "doc/path/doc.adoc"
          :scm {:commit "#SHA#"
                :url "https://github.com/user/project"}
          :uri-map {}}))))

(t/deftest scm-image-sourcehut-test
  (t/is
   (match?
    (m/nested-equals
     [[:img {:src "https://git.sr.ht/~user/project/blob/#SHA#/doc/path/rel.png"}]])
    (fix [[:img {:src "rel.png"}]]
         {:scm-file-path "doc/path/doc.adoc"
          :scm {:commit "#SHA#"
                :url "https://git.sr.ht/~user/project"}
          :uri-map {}}))))

(t/deftest current-replaced-with-version-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "/d/mygroupid/myartifactid/1.2.3"}
       "replace CURRENT for rendering lib"]
      [:a {:href "/d/mygroupid/myartifactid/1.2.3/api/foo.bar.baz#boingo?q=foobar"}
       "replace CURRENT when there are extra bits"]])
    (fix [[:a {:href "https://cljdoc.org/d/mygroupid/myartifactid/CURRENT"}
           "replace CURRENT for rendering lib"]
          [:a {:href "https://cljdoc.org/d/mygroupid/myartifactid/CURRENT/api/foo.bar.baz#boingo?q=foobar"}
           "replace CURRENT when there are extra bits"]]
         fix-opts))))

(t/deftest current-not-replaced-with-version-test
  (t/is
   (match?
    (m/nested-equals
     [[:a {:href "/d/mygroupid/not-myartifactid/CURRENT"}
       "doesn't replace CURRENT if not my artifact-id"]
      [:a {:href "/d/not-mygroupid/myartifactid/CURRENT"}
       "doesn't replace CURRENT if not my group-id"]
      [:a {:href "/d/not-mygroupid/not-myartifactid/CURRENT"}
       "doesn't replace CURRENT if not my group-id/artifact-id"]
      [:a {:href "/doopsie/mygroupid/myartifactid/CURRENT"}
       "doesn't replace CURRENT if not a cljdoc route"]
      [:a {:href "https://notcljdoc.org/d/mygroupid/myartifactid/CURRENT"
           :rel "nofollow"}
       "doesn't replace CURRENT if not a cljdoc url at all"]])
    (fix [[:a {:href "https://cljdoc.org/d/mygroupid/not-myartifactid/CURRENT"}
           "doesn't replace CURRENT if not my artifact-id"]
          [:a {:href "https://cljdoc.org/d/not-mygroupid/myartifactid/CURRENT"}
           "doesn't replace CURRENT if not my group-id"]
          [:a {:href "https://cljdoc.org/d/not-mygroupid/not-myartifactid/CURRENT"}
           "doesn't replace CURRENT if not my group-id/artifact-id"]
          [:a {:href "https://cljdoc.org/doopsie/mygroupid/myartifactid/CURRENT"}
           "doesn't replace CURRENT if not a cljdoc route"]
          [:a {:href "https://notcljdoc.org/d/mygroupid/myartifactid/CURRENT"}
           "doesn't replace CURRENT if not a cljdoc url at all"]]
         fix-opts))))
