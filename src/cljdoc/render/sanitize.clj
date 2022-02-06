(ns cljdoc.render.sanitize
  "Sanitize HTML

  Goals
  - disallow the blatantly dangerous, for example: `<script>` `onclick`, etc.
  - allow valid html generated from markdown styling
    - adoc generator includes `class` and very limited `style` attributes
    - md generator includes minimal `class`

  Of Interest
  - Authors writing markdown can include arbitrary HTML in their docs.
    It is just the nature of the adoc and md beasts.
    - We don't expect authors will typically want to, but if they wish, they
      can include `class` and `style` attributes in their arbitrary HTML that
      match what our markdown to HTML generators produce.
      Or more precisely, what our sanitizer allows through.
    - Not that they'd necessarly want to, but we don't want authors using cljdoc site
      stylings (currently tachyons) in their arbitrary HTML in their docs.

  Strategies
  - Start with a GitHub-like sanitizer config then tweak.
  - Adoc. I've taken an oldish adoc user manual (the one I included in cljdoc-exerciser)
    and analyzed the html produced by cljdoc.
    This should give us an idea of what we should allow through.
    This is pretty simple for most things, a little more complex for classes.
  - Md. Much simpler, barely includes any class and no style.
  - If a class attribute specifies an un-allowed css class, we'll
    just strip that un-allowed css class.
  - Log changes made by sanitizer to tags, attributes and attribute values.
    This will be server side only for now, but will help us with any support and to tweak
    our sanitizer config if necessary.

  Technical choice:
  - Chose java-html-sanitizer
  - Also considered JSoup clean, because we are already using JSoup.
    It did not have all the features of java-html-sanitizer, and right or wrong, I like
    that the OWASP folks have a single focus library, it makes me think they spent lots
    of time thinking about how to sanitize."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import (org.owasp.html AttributePolicy
                           ElementPolicy
                           FilterUrlByProtocolAttributePolicy
                           Handler
                           HtmlSanitizer
                           HtmlSanitizer$Policy
                           HtmlStreamEventProcessor$Processors
                           HtmlStreamEventReceiver
                           HtmlStreamRenderer
                           HtmlPolicyBuilder))
  (:require [clojure.string :as string]))

;; helpers for awkward interop
(defn- allow-tags [policy & tags]
  (.allowElements policy (into-array String tags)))

(defn- allow-attributes [policy & attrs]
  (.allowAttributes policy (into-array String attrs)))

(defn- with-protocols
  [attribute-policy & protocols]
  (.matching attribute-policy (FilterUrlByProtocolAttributePolicy. protocols)))

(defn- on-tags [attribute-policy & tags]
  (.onElements attribute-policy (into-array String tags)))

(defn- matching-vals [attribute-policy & expected-values]
  (-> attribute-policy
      (.matching (proxy [AttributePolicy] []
                   (apply [tag-name attribute-name value]
                     (when (some (fn [test-value]
                                   (if (string? test-value)
                                     (= test-value value)
                                     (re-matches test-value value)))
                                 expected-values)
                       value))))))

(defn- class-matches? [t v]
  (if (string? t)
    (= t v)
    (re-matches t v)))

(defn- sanitize-classes
  "sanitize class-combos where a class-combo is:
  [marker-class optional-class...]
  where marker-class and optional-class can be a string or regular expression.

  We match a class-combo on marker-class and then check that any other classes match optional-classes.
  This seems to work well for our current use case.
  Adjust as necessary if/when that stops being the case."
  [policy & class-combos]
  ;; adoc roles can, I think, appear in any class attribute.
  ;; note that there are deprecated adoc roles such as colors (ex. `red` `background-green` etc) and
  ;; `big` and `small`, we can add support for these if it makes sense to cljdoc and its users.
  (let [global-classes ["underline" "overline" "line-through" "nobreak" "nowrap" "pre-wrap"]]
    (-> policy
        (allow-attributes "class")
        (.matching (proxy [AttributePolicy] []
                     (apply [tag-name attribute-name value]
                       (let [cur-classes (string/split value #" +")
                             matching-combo (reduce (fn [_acc class-combo]
                                                      (let [marker-class (first class-combo)]
                                                        (when (some #(class-matches? marker-class %) cur-classes)
                                                          (reduced class-combo))))
                                                    nil
                                                    class-combos)
                             valid-classes (concat matching-combo global-classes)
                             [valid invalid] (reduce (fn [[valid invalid] cur-class]
                                                       (if (some #(class-matches? % cur-class) valid-classes)
                                                         [(conj valid cur-class) invalid]
                                                         [valid (conj invalid cur-class)]))
                                                     [[] []]
                                                     cur-classes)]
                         (if (seq invalid)
                           (when (seq valid)
                             (string/join " " valid))
                           value))))))))

(defn- allow-tag-with-attribute-value
  "If tag does not include required-attribute with required-value, it will be dropped.
  We deal with this at the tag level (rather than the attribute level) to allow dropping
  a tag based on its attributes (rather than just dropping the attribute only)."
  [policy tag required-attribute required-value]
  (-> policy
      (.allowElements (proxy [ElementPolicy] []
                        (apply [tag-name av-pairs]
                          (let [avs (->> av-pairs (partition 2) (map vec) (into {}))
                                actual-value (get avs required-attribute nil)]
                            (when (= required-value actual-value)
                              tag-name))))
                      (into-array String [tag]))))

(def ^:private policy
  (-> (HtmlPolicyBuilder.)

      ;; github pipeline sanitization ref:
      ;; https://github.com/gjtorikian/html-pipeline/blob/0e3d84eb13845e0d2521ef0dc13c9c51b88e5087/lib/html/pipeline/sanitization_filter.rb#L44-L106

      (allow-tags
       ;; from github pipeline
       "a" "abbr" "b" "bdo" "blockquote" "br" "caption" "cite" "code"
       "dd" "del" "details" "dfn" "div" "dl" "dt" "em" "figcaption"
       "figure" "h1" "h2" "h3" "h4" "h5" "h6" "h7" "h8" "hr"
       "i" "img" "ins" "kbd" "li" "mark" "ol" "p" "pre" "q" "rp" "rt" "ruby"
       "s" "samp" "small" "span" "strike" "strong" "sub" "summary" "sup"
       "table" "tbody" "td" "tfoot" "th" "thead" "time" "tr" "tt" "ul" "var" "wbr"
       ;; adoc extras
       "col" "colgroup" "input" "u")
      (allow-attributes
       ;; from (former?) github pipeline, seems a bit permissive, but if gh was happy, probably fine
       "abbr" "accept" "accept-charset"
       "accesskey" "action" "align" "alt"
       "aria-describedby" "aria-hidden" "aria-label" "aria-labelledby"
       "axis" "border" "cellpadding" "cellspacing" "char"
       "charoff" "charset" "charset" "checked" "clear"
       "color" "cols" "colspan"
       "compact" "coords" "datetime" "describedby"
       "dir" "disabled" "enctype" "for"
       "frame" "headers" "height" "hidden"
       "hreflang" "hspace" "ismap"
       "itemprop" "label" "label" "labelledby"
       "lang" "maxlength" "media"
       "method" "multiple" "name" "nohref"
       "noshade" "nowrap" "open" "progress" "prompt" "readonly" "rel"
       "rev" "role" "rows" "rowspan" "rules"
       "scope" "selected" "shape" "size"
       "span" "start" "summary" "tabindex"
       "target" "title" "type" "usemap" "valign"
       "value" "vspace" "width")
      (.globally)

      ;; id attribute
      ;; assuming  HTML5 ids are safe which is basically no spaces and more than 1 char.
      (allow-attributes "id")
      (matching-vals #"\S+")
      (.globally)

      ;;
      ;; protocols, config inspired by github pipeline
      ;;

      ;; superset of all allowed protocols
      (.allowUrlProtocols (into-array String ["http" "https" "mailto" "xmpp" "irc" "ircs" "viewsource"]))

      (allow-attributes "href")
      (with-protocols "http" "https" "mailto" "xmpp" "irc" "ircs" "viewsource")
      (on-tags "a")

      (allow-attributes "src" "longdesc")
      (with-protocols "http" "https")
      (on-tags "img")

      (allow-attributes "cite")
      (with-protocols "http" "https")
      (on-tags "blockquote" "del" "ins" "q")

      ;;
      ;; specific attribute config
      ;;

      ;; for md wikilink support
      (allow-attributes "data-source")
      (matching-vals "wikilink")
      (on-tags "a")

      ;; mimic from github pipeline
      (allow-attributes "itemscope" "itemtype")
      (on-tags "div")

      ;; style restrictions for adoc
      (allow-attributes "style")
      (matching-vals #"width: \d+(\.\d+)?%;?")
      (on-tags "col" "table")

      ;; adoc can show checkboxes, but that's the only input type that we should allow
      (allow-tag-with-attribute-value "input" "type" "checkbox")

      (allow-attributes "data-item-complete"
                        "checked")
      (on-tags "input")

      ;; adoc allows reverse order lists
      (allow-attributes "reversed")
      (on-tags "ol")

      ;; allow data-lang on code blocks for formatting support
      (allow-attributes "data-lang")
      (on-tags "code")

      ;;
      ;; class - we'd like markdown styles to get through but not for
      ;;         our users to do their own styling.
      ;;       - assume adoc unless commented with md
      ;;       - letting classes through allows us the freedom to style elements - but only if we want to.
      ;;       - if some combo of classes aren't gettting through that is required for some sort of desired
      ;;         styling, probably something I just missed, adjust as necessary
      ;;
      ;; Ref: https://github.com/asciidoctor/asciidoctor/blob/main/src/stylesheets/asciidoctor.css
      ;;
      ;; currently not allowing (have not looked into)
      ;; - audioblock
      ;; - clearfix
      ;; - fa-* ;; font awesome classses, we don't currently use these
      ;; - float-group
      ;; - icon-caution
      ;; - icon-important
      ;; - icon-note
      ;; - icon-tip
      ;; - icon-warning
      ;; - toc-right
      ;; - videoblock
      ;; and I think these relate to highlighting featues which we do not use
      ;; - highlight
      ;; - linenos
      ;; - linenotable
      ;; - linenums
      ;; - prettyprint
      ;; I think these are maybe obsolete? or obscure?
      ;; - output
      ;; - subheader
      ;; - toc2
      ;; - tr.even
      (sanitize-classes
       ["md-anchor"] ;; md
       ["anchor"]
       ["bare"]
       ["footnote"]
       ["image"]
       ["link"])
      (on-tags "a")

      (sanitize-classes
       ["button"]
       ["caret"]
       ["conum"]
       ["menu"]
       ["menuref"]
       ["menuitem"]
       ["submenu"])
      (on-tags "b")

      (sanitize-classes ["title"])
      (on-tags "caption")

      (sanitize-classes
       [#"language-[-+_.a-zA-Z0-9]+"] ;; md & adoc
       ["code"])
      (on-tags "code")

      (sanitize-classes
       ["admonitionblock" #"(caution|important|note|tip|warning)"]
       ["attribution"]
       ["colist" "arabic"] ;; -- arabic only ??
       ["content"]
       ["details"]
       ["dlist" "gloassary"]
       ["exampleblock"]
       ["footnote"]
       ["hdlist"]
       ["imageblock" "left" "right" "thumb" "text-center" "text-right" "text-left"]
       ["listingblock"]
       ["literal"]
       ["literalblock"]
       ["olist" #"(arabic|decimal|lower(alpha|greek|roman)|upper(alpha|roman))"]
       ["openblock" "partintro"]
       ["paragraph" "lead"]
       ["qlist" "qanda"]
       ["quoteblock" "abstract"]
       [#"sect[0-6]"]
       ["sectionbody"]
       ["sidebarblock"]
       ["stemblock"]
       ["title"]
       ["toc"]
       ["ulist" #"(bibliography|checklist|square)"]
       ["verseblock"])
      (on-tags "div")

      (sanitize-classes
       ["hdlist1"])
      (on-tags "dt")

      (sanitize-classes
       ["path"]
       ["term"])
      (on-tags "em")

      (sanitize-classes
       ["float"]
       ["sect0"])
      (on-tags "h1")

      (sanitize-classes
       ["float"])
      (on-tags "h2" "h3" "h4" "h5" "h6")

      (sanitize-classes
       ["arabic"]
       ["decimal"]
       ["loweralpha"]
       ["upperalpha"]
       ["lowergreek"]
       ["lowerroman"]
       ["upperroman"]
       ["no-bullet"]
       ["unnumbered"]
       ["unstyled"])
      (on-tags "ol")

      (sanitize-classes
       ["tableblock"]
       ["quoteblock"])
      (on-tags "p")

      (sanitize-classes
       ["content"]
       ["highlight"])
      (on-tags "pre")

      (sanitize-classes
       ["alt"]
       ["icon"]
       ["image" "left" "right"]
       ["keyseq"]
       ["menuseq"])
      (on-tags "span")

      (sanitize-classes
       ["footnote"]
       ["footnoteref"])
      (on-tags "sup")

      (sanitize-classes
       ["tableblock"
        #"frame-(all|sides|ends|none)"
        #"grid-(all|cols|rows|none)"
        #"stripes-(even|odd|all|hover)"
        "fit-content" "stretch" "unstyled"])
      (on-tags "table")

      (sanitize-classes
       ["content"]
       ["hdlist1"]
       ["hdlist2"]
       ["icon"]
       ["tableblock" #"halign-(left|center|right)" #"valign-(top|middle|bottom)"])
      (on-tags "td" "th")

      (sanitize-classes
       ["bibliography"]
       ["checklist"]
       ["circle"]
       ["disc"]
       ["inline"]
       ["none"]
       ["no-bullet"]
       [#"sectlevel[0-6]"]
       ["square"]
       ["unstyled"])
      (on-tags "ul")

      (.toFactory)))

(defn clean*
  "The java-html-sanitizer HtmlChangeReporter only supports reporting on discarded tags and attributes, and
  not attribute values.
  We essentially mimic its strategy here but support reporting on attribute values.

  The strategy is:
  - what the policy removed will not be rendered
  - so, compare tag, by tag what is passed to policy vs what is actually rendered
  This little lower-level complexity removes multiple higher-level work-arounds.

  Careful in here, Java lists are mutable, so make copies."
  [html]
  (if (not html)
    {:cleaned ""
     :changes []}
    (let [changes (atom [])
          change-tracker (atom {})
          out (StringBuilder. (count html))
          renderer (HtmlStreamRenderer/create out Handler/DO_NOTHING)
          wrapped-renderer (proxy [HtmlStreamEventReceiver] []
                             (openDocument [] (.openDocument renderer))
                             (closeDocument [] (.closeDocument renderer))
                             (openTag [tag av-pairs]
                               (reset! change-tracker {:rendered-tag tag
                                                       :rendered-av-pairs (into [] av-pairs)})
                               (.openTag renderer tag av-pairs))
                             (closeTag [tag-name] (.closeTag renderer tag-name))
                             (text [text] (.text renderer text)))
          wrapped-policy (.apply policy wrapped-renderer)
          wrapped-policy (proxy [HtmlSanitizer$Policy] []
                           (openDocument [] (.openDocument wrapped-policy))
                           (closeDocument [] (.closeDocument wrapped-policy))
                           (openTag [tag av-pairs]
                             (let [orig-av-pairs (into [] av-pairs)]
                               (reset! change-tracker {})
                               (.openTag wrapped-policy tag av-pairs)
                               (let [{:keys [rendered-tag rendered-av-pairs]} @change-tracker]
                                 (reset! change-tracker {})
                                 (if (not= rendered-tag tag)
                                   (swap! changes conj {:type :removed
                                                        :tag tag
                                                        :attributes (partition 2 orig-av-pairs)})
                                   (when (not= orig-av-pairs rendered-av-pairs)
                                     (swap! changes conj {:type :modified
                                                          :tag tag
                                                          :old-attributes (partition 2 orig-av-pairs)
                                                          :new-attributes (partition 2 rendered-av-pairs)}))))))
                           (closeTag [tag-name] (.closeTag wrapped-policy tag-name))
                           (text [text] (.text wrapped-policy text)))]
      (HtmlSanitizer/sanitize
       html
       wrapped-policy
       HtmlStreamEventProcessor$Processors/IDENTITY)
      {:cleaned (.toString out)
       :changes @changes})))

(defn- triage-changes
  "Attach a log level to changes."
  [changes]
  (for [c changes]
    (if (= :removed (:type c))
      (assoc c :level :info)
      (let [new-attributes (->> c
                                :new-attributes
                                (map vec)
                                (into {}))
            ;; drive from old attributes, we don't care much if:
            ;; - the sanitizer has added attributes
            ;; - the sanitizer has changed attribute values by encoding only
            ;; - the new and old attribute values are the same
            interesting-changes (->> (:old-attributes c)
                                     (remove (fn [[old-attr old-value]]
                                               (let [new-value (get new-attributes old-attr nil)]
                                                 (or (= old-value new-value)
                                                     ;; assume that href/src, if present in both old and new is an encoding change
                                                     (and new-value (some #{old-attr} ["href" "src" "longdesc" "cite"])))))))]
        (if (seq interesting-changes)
          (assoc c :level :info)
          (assoc c :level :debug))))))

(defn- log-changes [changes]
  (doseq [{:keys [level] :as c} changes]
    (log/log level (pr-str (dissoc c :level)))))

(defn clean
  "Returns cleaned html string for given `html`.
  Changes are logged:
  - I don't see a need to format to text, just log the edn for now
  - Some changes are uninteresting, like attribute addditions or simple escaping of urls,
    these are currently logged anyway but at debug, rather than info level."
  [html]
  (let [{:keys [cleaned changes]} (clean* html)]
    (-> changes
        distinct ;; duplicates findings do not add value
        triage-changes
        log-changes)
    cleaned))

(comment

  (clean* "<div style=\"width: 10.3%;\">hiya</div>")

  (clean* "<h1 bad='foo' id='ok' class='float boat'>")

  (clean* "<hippo bad='foo' apple>")

  (clean* "<img src='booya.png'>")
  (clean* "<img src='mailto:bing@bang.com'>")

  (clean* "<a href='view-source:asciidoctor.org' target='_blank' rel='noopener'>Asciidoctor homepage</a>")

  (clean* "<a href='mailto:foo@bar.com'>hey</a>")

  ;; only using single quotes because they are easier on the eyes, sanitizer converts
  (clean "<h1 id='3'>hi</h1> <script>alert('hey');</script>")
  (clean "<q cite='https://boo.com'>hey</cite>")

  (clean* "<h1 id='***'>boo</h1>")

  (clean "<input>")

  (clean "<input type='text' checked chucked>")
  (clean "<input type='checkbox' checked chucked>")

  (clean* "<a href='view-source:asciidoctor.org' target='_blank' rel='noopener'>Asciidoctor homepage</a>")

  (clean* "<a href='mailto:foo@bar.com'>hey</a>")

  (clean "<a href='https://foo.you'>hey</a>");; => "hey"
  (clean "<a href=' '>hey</a>");; => "hey"

  (clean "<a class='amd-anchor foo' href='#' nope='dope'>hmm</a>")

  (clean "<a class='md-anchor foo underline' href='#'>hmm</a>")
  (clean "<a class='underline md-anchor foo' href='#'>hmm</a>")

  (clean "<caption class='title'>Yup</caption>")
  (clean "<caption class='underline'>Yup</caption>")
  (clean "<caption>Yup</caption>")

  (clean "<code class='code'>boo</code>")

  (clean "<code class='language-foo.ffoo'>boo</code>")

  (clean "<img src='https://boo.com/ha().png'>")
  (clean "<img src='https://boo.com/ha().png' uhno>")

  (distinct [8 1 22 1 3 8])

  (clean "<img src='mailto:lee@dlread.com'>")

  (clean "<table style='width: 13.5%;'></table>")
  (clean "<table style='width: 10px;'></table>")

  (clean "<table style='not: allowed;'>");; => "<table></table>"

  (clean "<div class=\"ok foo nice\">hey ho</div>")

  (clean "<table class='tableblock frame-ends grid-all unstyled2'>")

  (clean "<a class=\"md-anchor\" href=\"#\">iii</a>")
  (clean "<a class=\"language-foopack.roo\" href=\"#\">iii</a>")

  (clean* "<a href=\"https://opencollective.com/boot-clj/sponsor/4/website\" target=\"_blank\">boo</a>")

  (clean* "<a href=\"https://opencollective.com/boot-clj/sponsor/0/website\" target=\"_blank\"><img src=\"https://opencollective.com/boot-clj/sponsor/0/avatar.svg\"></a>")

  (spit "leetest-cleaned.html" (clean (slurp "leetest.html"))))
