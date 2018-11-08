(ns cljdoc.render.build-log
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.util :as util]
            [cljdoc.util.datetime :as dt]
            [cljdoc.server.routes :as routes])
  (:import [java.time Instant Duration ZoneId]
           [java.time.temporal ChronoUnit]))

(defn done? [build-info]
  (boolean
   (or (:import_completed_ts build-info)
       (:error build-info))))

(defn section [date & contents]
  (when date
    [:div.cf.ba.b--moon-gray.mb2.br1.bg-washed-yellow
     [:div.fl-ns.w-third-ns.bb.bn-ns.b--moon-gray.pa3
      [:span date]]
     (into [:div.fl-ns.w-two-thirds-ns.bg-white.bl-ns.b--moon-gray.pa3] contents)]))

(defn cljdoc-link [build-info icon?]
  (let [cljdoc-uri (routes/url-for :artifact/version
                                   :path-params
                                   {:group-id (:group_id build-info)
                                    :artifact-id (:artifact_id build-info)
                                    :version (:version build-info)})]
    [:a.link.blue {:href cljdoc-uri}
     (when icon?
       [:img.v-mid.mr2 {:src "https://icon.now.sh/chevron/20"}])
     (str "cljdoc.org" cljdoc-uri)]))

(defn scm-info [build-info]
  (if (and (:scm_url build-info) (:commit_sha build-info))
    [:p.bg-washed-green.pa3.br2
     "Everything looking splendid!"]
    [:div
     [:p.bg-washed-red.pa3.br2
      "We could not find the git repository for your project or
             link a commit to this release. API docs work regardless
             of this but consider "
      [:a.link.blue {:href (util/github-url :userguide/scm-faq)}
       "setting these for optimal results"] "."]
     [:dl
      [:dt.b.mv2 "SCM URL"]
      [:dd.ml0 (or (:scm_url build-info) "nil")]

      [:dt.b.mv2 "Commit SHA"]
      [:dd.ml0 (or (:commit_sha build-info) "nil")]]]))

(defn git-import-explainer [build-info]
  [:div.lh-copy
   [:p "cljdoc allows you to combine API docs with "
    [:a.link.blue {:href (util/github-url :userguide/articles)} "articles"]
    " from your Git repository. By default we import just the Readme."
    (when (:git_problem build-info)
      [:span " In this case there was a problem " [:code.f7.bg-washed-red.br2.pa1.ph2 (:git_problem build-info)]
       " importing from Git, but don't worry — "
       [:b.fw6 "API docs will work regardless."]])]
   (when (= "unknown-revision" (:git_problem build-info))
     [:p [:code.f7.bg-washed-red.br2.pa1.ph2 (:git_problem build-info)]
      " This issue may occur if you deployed to Clojars before
     pushing the Git commit the release was made at."])
   (when (:git_problem build-info)
     [:p "To fix this issue, check out the FAQ on "
      [:a.link.blue {:href (util/github-url :userguide/scm-faq)} "properly setting SCM information"]])])

(defn build-page [build-info]
  (->> [:div.mw8.center.pa2.pv5
        ;; [:pre.pa3 (pr-str build-info)]

        [:span.gray "cljdoc build #" (:id build-info)]
        [:h1.mt2.mb4
         (str (:group_id build-info) "/" (:artifact_id build-info))
         [:span " v" (:version build-info)]]

        (when-not (done? build-info)
          [:div.pa3.ba.b--blue.mb3.lh-copy.ma0.br2.bw1.f4
           [:p.ma0.mb2 "We will now queue a job on CircleCI that will analyze
           your code in an isolated environment. Once this is done we
           will process the result of the analysis and integrate
           additional sources like your Git repository."]
           [:p.ma0 " You can follow the build's progress here. This
           page will automatically reload until your build is
           done."]])

        (section
         (:analysis_requested_ts build-info)
         [:h3.mt0 "Analysis Requested"])

        (section
         (:analysis_triggered_ts build-info)
         [:h3.mt0 "Analysis Kicked Off"]
         (when-let [job-uri (:analysis_job_uri build-info)]
           [:a.link.blue {:href job-uri}
            [:img.v-mid.mr2 {:src "https://icon.now.sh/circleci/20"}]
            "View job on CircleCI"]))

        (section
         (:analysis_received_ts build-info)
         [:h3.mt0 "Analysis Received"]
         [:a.link.blue
          {:href (if (some-> (:analysis_result_uri build-info) (.startsWith  "/"))
                   (str "file://" (:analysis_result_uri build-info))
                   (:analysis_result_uri build-info))}
          "view result"])

        (section
         (:api_imported_ts build-info)
         [:h3.mt0 "API Import"]
         [:p.bg-washed-green.pa3.br2.ma0 "API imported successfully"]
         [:p.mb0 (cljdoc-link build-info true)])

        (when (:error build-info)
          (section
           ""
           [:h3.mt0 "There was an error"]
           [:p.bg-washed-red.pa3.br2 (:error build-info)]
           [:p.lh-copy "Please see the " [:a.link.blue {:href (:analysis_job_uri
           build-info)} "build job"] " to understand why this build
           failed and reach out if you aren't sure how to fix the issue."]
           #_[:p (cljdoc-link build-info true)]))

        (cond
          (and (:api_imported_ts build-info)
               (not (or (:git_problem build-info) (:git_imported_ts build-info))))
          (section
           ""
           [:h3.mt0 "Git Import"]
           (git-import-explainer build-info)
           [:p.ba.b--blue.pa3.br2.ma0 "in progress.."])

          (:git_imported_ts build-info)
          (section
           (:git_imported_ts build-info)
           [:h3.mt0 "Git Import Completed"]
           (git-import-explainer build-info)

           (scm-info build-info)

           [:p (cljdoc-link build-info true)]
           (when (and (:scm_url build-info) (:commit_sha build-info))
             [:p
              [:a.link.blue {:href (:scm_url build-info)}
               [:img.v-mid.mr2 {:src "https://icon.now.sh/github/20"}]
               (subs (:scm_url build-info) 19)]
              " @ "
              [:a.link.blue {:href (str (:scm_url build-info) "/commit/" (:commit_sha build-info))}
               (if (< (count (:commit_sha build-info)) 8)
                 (:commit_sha build-info)
                 (subs (:commit_sha build-info) 0 8))]]))

          (:git_problem build-info)
          (section
           ""
           [:h3.mt0 "Git Import"]
           (git-import-explainer build-info)
           [:p (cljdoc-link build-info true)]))

        (when-not (done? build-info)
          [:script
           "setTimeout(function(){window.location.reload(1);}, 5000);"])

        ;; [:p [:code [:pre (with-out-str (clojure.pprint/pprint build-info))]]] ;DEBUG
        [:p.lh-copy.dark-gray "Having trouble? Please reach out via "
        [:a.link.blue {:href
        "https://clojurians.slack.com/messages/C8V0BQ0M6/"} "Slack"] "
        or " [:a.link.blue {:href (util/github-url :issues)} "open an
        issue on GitHub"] ". Thanks!"]]

       (layout/page {:title (str "cljdoc build #" (:id build-info))})))

(defn seconds-diff [d1 d2]
  (let [s (.getSeconds
           (Duration/between
            (Instant/parse d1)
            (Instant/parse d2)))]
    (str "+" s "s")))

(defn build-aggregates
  "Given builds, calculate aggregate values.
  Returns a map containing total builds, failed builds, failure percentage."
  [builds]
  (let [build-cnt (count builds)
        failed-build-cnt (->> builds
                              (filter #(some? (:error %)))
                              count)]
    {:date (-> builds
               first
               :analysis_triggered_ts
               dt/->analytics-format)
     :total build-cnt
     :failed failed-build-cnt
     :percent-failed (* 100 (/ failed-build-cnt build-cnt))}))

(defn build-analytics
  [build-aggregates]
  (let [days   (take 5 build-aggregates)
        stddev (Math/sqrt (util/variance (map :percent-failed days)))
        mean   (util/mean (map :percent-failed days))
        too-high? (fn [v] (< (+ mean stddev) v))]
    [:div.mb2
     (->> build-aggregates
          (take 5)
          (map (fn [{:keys [date total failed percent-failed]}]
                 [:dl.dib.w-20
                  [:dd.f6.ml0 date]
                  [:dd.f4.b.ml0
                   {:class (when (too-high? percent-failed) "dark-red")}
                   (str (int percent-failed) "% failed")]
                  [:dd.f6.ml0 (str failed "/" total)]])))]))

(defn builds-page [builds]
  (->> (for [b (take 100 builds)]
         [:div.br2.ba.b--moon-gray.mb2
          [:div.cf.pa3
           [:div.fl
            [:h3.ma0.mb2 (:group_id b) "/" (:artifact_id b) " " (:version b)
             [:a.link.ml2.f5 {:href (str "/builds/" (:id b))}
              [:span.silver.normal "#" (:id b)]]]
            (cljdoc-link b false)]
           [:div.fr
            (when (done? b)
              (cond
                (and (:scm_url b) (:commit_sha b)) [:span.db.bg-washed-green.pa3.br2 "Good"]
                (and (:import_completed_ts b)
                     (:git_problem b))        [:span.db.bg-washed-yellow.pa3.br2 (str "Git: " (:git_problem b))]
                (and (:import_completed_ts b)
                     (not (:scm_url b)))           [:span.db.bg-washed-yellow.pa3.br2 "SCM URL missing"]
                (some-> (:error b)
                        (.startsWith "cljdoc.analysis.git")) [:span.db.bg-washed-yellow.pa3.br2 (:error b)]
                (:error b) [:span.db.bg-washed-red.pa3.br2 (:error b)]))]]
          [:div.cf.tc.bt.b--moon-gray.pa2.o-30
           ;; (def requested (:analysis_requested_ts b))
           ;; (def completed (:import_completed_ts b))
           [:div.fl.w-25.br.b--moon-gray
            [:span.db.f7.ttu.tracked.silver "Analysis requested"]
            (:analysis_requested_ts b)]
           [:div.fl.w-25
            [:span.db.f7.ttu.tracked.gray "Analysis job triggered"]
            (when (:analysis_triggered_ts b)
              (seconds-diff
               (:analysis_requested_ts b)
               (:analysis_triggered_ts b)))]
           [:div.fl.w-25
            [:span.db.f7.ttu.tracked.gray "Analysis data received"]
            (when (:analysis_received_ts b)
              (seconds-diff
               (:analysis_requested_ts b)
               (:analysis_received_ts b)))]
           [:div.fl.w-25
            [:span.db.f7.ttu.tracked.gray "Import completed"]
            (cond
              (:import_completed_ts b) (seconds-diff
                                        (:analysis_requested_ts b)
                                        (:import_completed_ts b))
              (:error b)               "failed"
              :else                    "in progress")]]])
       (into [:div.mw8.center.pv3.ph2
              [:h1 "Recent cljdoc builds"]
              (->> builds
                   (partition-by #(-> % :analysis_triggered_ts Instant/parse
                                      (.truncatedTo  ChronoUnit/DAYS)))
                   (map build-aggregates)
                   (build-analytics))])

   (layout/page {:title "cljdoc builds"})))
