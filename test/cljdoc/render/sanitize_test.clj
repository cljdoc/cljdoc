(ns cljdoc.render.sanitize-test
  "Some spot checks for our html sanitizer"
  (:require [cljdoc.render.sanitize :as sanitize]
            [clojure.test :as t]))

(t/deftest discards-harmful-attributes
  (t/is (= {:changes [{:type :modified
                       :tag "div"
                       :old-attributes [["onclick" "alert('boo');"]]
                       :new-attributes []}]
            :cleaned "<div>some content</div>"}
           (sanitize/clean* "<div onclick=\"alert('boo');\">some content</div>"))))

(t/deftest discards-harmful-tags
  (t/is (= {:changes [{:type :removed
                       :tag "script"
                       :attributes []}]
            :cleaned "<div>some content</div>"}
           (sanitize/clean* "<div>some content<script>alert('boo');</script></div>"))))

(t/deftest restricts-protocols
  (t/is (= {:changes [{:type :removed
                       :tag "a"
                       :attributes [["href" "nogoproto://something"]]}]
            :cleaned "lookey here"}
           (sanitize/clean* "<a href=\"nogoproto://something\">lookey here</a>")))
  (t/is (= {:changes [{:type :removed
                       :tag "img"
                       :attributes [["src" "mailto:john.smith@example.com"]]}]
            :cleaned ""}
           (sanitize/clean* "<img src=\"mailto:john.smith@example.com\"")))
  (t/is (= {:changes []
            :cleaned "<q cite=\"https://boo.com\">hey</q>"}
           (sanitize/clean* "<q cite='https://boo.com'>hey</q>"))))

(t/deftest handles-style
  ;; we allow specific width styling for adoc on table and col only
  (t/is (= {:changes []
            :cleaned "<table style=\"width: 10.3%;\"></table>"}
           (sanitize/clean* "<table style=\"width: 10.3%;\"></table>")))
  ;; notice that the sanitizer also completes any incomplete elements
  (t/is (= {:changes []
            :cleaned "<table><colgroup><col style=\"width: 5%;\" /></colgroup></table>"}
           (sanitize/clean* "<col style=\"width: 5%;\"></col>")))
  ;; we only allow % widths
  (t/is (= {:changes [{:type :modified
                       :tag "table"
                       :old-attributes [["style" "width: 10px;"]]
                       :new-attributes []}]
            :cleaned "<table></table>"}
           (sanitize/clean* "<table style=\"width: 10px;\"></table>")))
  ;; and disallow styles on other tags
  (t/is (= {:changes [{:type :modified
                       :tag "div"
                       :old-attributes [["style" "width: 10.3%;"]]
                       :new-attributes []}]
            :cleaned "<div>hiya</div>"}
           (sanitize/clean* "<div style=\"width: 10.3%;\">hiya</div>"))))

(t/deftest handles-classes
  ;; we make a reasonable attempt to only allow class values through that occur
  ;; from markdown->html rendering
  (t/is (= {:changes []
            :cleaned "<code class=\"language-some-language-here\">code here</code>"}
           (sanitize/clean* "<code class=\"language-some-language-here\">code here</code>")))
  ;; we discard what we don't recognize
  (t/is (= {:changes [{:type :modified
                       :tag "div"
                       :old-attributes [["class" "admonitionblock mt5-ns mw7 center pa4 pa0-l caution"]]
                       :new-attributes [["class" "admonitionblock caution"]]}]
            :cleaned "<div class=\"admonitionblock caution\">caution text</div>"}
           (sanitize/clean* "<div class=\"admonitionblock mt5-ns mw7 center pa4 pa0-l caution\">caution text</div>")))
  ;; generic adoc roles such as nowrap, nobreak etc should be allowed on their own...
  (t/is (= {:changes []
            :cleaned "<p class=\"nowrap\">paragraph text</p>"}
           (sanitize/clean* "<p class=\"nowrap\">paragraph text</p>")))
  ;; ...and when combined with other valid (and invalid) classes
  (t/is (= {:changes [{:type :modified
                       :tag "table"
                       :old-attributes [["class" "tableblock frame-ends nobreak whazzup grid-rows fit-content"]]
                       :new-attributes [["class" "tableblock frame-ends nobreak grid-rows fit-content"]]}]
            :cleaned "<table class=\"tableblock frame-ends nobreak grid-rows fit-content\"></table>"}
           (sanitize/clean* "<table class=\"tableblock frame-ends nobreak whazzup grid-rows fit-content\"></table>"))))

(t/deftest handles-input
  ;; adoc will render input checkboxes, if we see any other type of input tag, it will be dropped.
  ;; a valid checkbox with an invalid attribute
  (t/is (= {:changes [{:type :modified
                       :tag "input"
                       :old-attributes [["type" "checkbox"] ["checked" "checked"] ["chucked" "chucked"]]
                       :new-attributes [["type" "checkbox"] ["checked" "checked"]]}]
            :cleaned "<input type=\"checkbox\" checked=\"checked\" />"}
           (sanitize/clean* "<input type='checkbox' checked chucked>")))
  ;; an invalid input
  (t/is (= {:changes [{:type :removed
                       :tag "input"
                       :attributes [["type" "text"] ["checked" "checked"] ["chucked" "chucked"]]}]
            :cleaned ""}
           (sanitize/clean* "<input type='text' checked chucked>"))))

(t/deftest validates-id-attribute
  (t/is (= {:changes []
            :cleaned "<h1 id=\"μ\">hiya</h1>"}
           (sanitize/clean* "<h1 id=\"μ\">hiya</h1>")))
  (t/is (= {:changes [{:type :modified
                       :tag "h1"
                       :old-attributes [["id" ""]]
                       :new-attributes []}]
            :cleaned "<h1>hiya</h1>"}
           (sanitize/clean* "<h1 id=\"\">hiya</h1>")))
  (t/is (= {:changes [{:type :modified
                       :tag "h1"
                       :old-attributes [["id" "a "]]
                       :new-attributes []}]
            :cleaned "<h1>hiya</h1>"}
           (sanitize/clean* "<h1 id=\"a \">hiya</h1>"))))

(t/deftest deals-with-wikilinks
  (t/is (= {:changes []
            :cleaned "<a href=\"/d/some/link\" data-source=\"wikilink\">sometext</a>"}
           (sanitize/clean* "<a href=\"/d/some/link\" data-source=\"wikilink\">sometext</a>"))))

(t/deftest nesting-does-not-confuse
  (t/is (= {:changes [{:type :modified
                       :tag "a"
                       :old-attributes [["href" "https://example.com"] ["target" "_blank"]]
                       :new-attributes [["href" "https://example.com"] ["target" "_blank"]
                                        ;; notice in this case, the sanitizer added some attributes
                                        ["rel" "noopener noreferrer"]]}]
            :cleaned "<a href=\"https://example.com\" target=\"_blank\" rel=\"noopener noreferrer\"><img src=\"https://example.com/avatar.svg\" /></a>"}
           (sanitize/clean* "<a href=\"https://example.com\" target=\"_blank\"><img src=\"https://example.com/avatar.svg\"></a>")))
  ;; repeat but this time both parent and nested have changes
  (t/is (= {:changes [{:type :modified
                       :tag "a"
                       :old-attributes [["href" "https://example.com"] ["target" "_blank"]]
                       :new-attributes [["href" "https://example.com"] ["target" "_blank"]
                                        ["rel" "noopener noreferrer"]]}
                      {:type :modified
                       :tag "img"
                       :old-attributes [["src" "https://example.com/avatar.svg"] ["uhno" "uhno"]]
                       :new-attributes [["src" "https://example.com/avatar.svg"]]}]
            :cleaned "<a href=\"https://example.com\" target=\"_blank\" rel=\"noopener noreferrer\"><img src=\"https://example.com/avatar.svg\" /></a>"}
           (sanitize/clean* "<a href=\"https://example.com\" target=\"_blank\"><img src=\"https://example.com/avatar.svg\" uhno></a>")))
  ;; when parent is removed, valid children are preserved
  (t/is (= {:changes [{:type :removed
                       :tag "notatag"
                       :attributes [["what" "dis"]]}
                      {:type :modified
                       :tag "div"
                       :old-attributes [["id" "ok"] ["uhno" "uhno"]]
                       :new-attributes [["id" "ok"]]}]
            :cleaned "<div id=\"ok\">something</div>"}
           (sanitize/clean* "<notatag what=\"dis\"><div id=\"ok\" uhno>something</div></notatag>"))))
