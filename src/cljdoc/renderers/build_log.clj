(ns cljdoc.renderers.build-log
  (:require [cljdoc.renderers.common :as common]
            [cljdoc.routes :as routes])
  (:import [java.time Instant Duration]))

(defn section [date & contents]
  (when date
    [:div.cf.ba.b--moon-gray.mb2.br1.bg-washed-yellow
     [:div.fl-ns.w-third-ns.bb.bn-ns.b--moon-gray.pa3
      [:span date]]
     (into [:div.fl-ns.w-two-thirds-ns.bg-white.bl-ns.b--moon-gray.pa3] contents)]))

(defn cljdoc-link [build-info icon?]
  (let [cljdoc-uri (routes/path-for :artifact/version
                                    {:group-id (:group_id build-info)
                                     :artifact-id (:artifact_id build-info)
                                     :version (:version build-info)})]
    [:a.link.blue {:href cljdoc-uri}
     (when icon?
       [:img.v-mid.mr2 {:src "https://icon.now.sh/chevron/24"}])
     (str "cljdoc.xyz" cljdoc-uri)]))

(defn scm-info [build-info]
  (if (and (:scm_url build-info) (:commit_sha build-info))
    [:p.bg-washed-green.pa3.br2
     "Everything looking splendid!"]
    [:div
     [:p.bg-washed-red.pa3.br2
      "We could not find the git repository for your project or
             link a commit to this release. API docs work regardless
             of this but consider "
      [:a.link.blue {:href (common/github-url :userguide/scm-faq)}
       "setting these for optimal results"] "."]
     [:dl
      [:dt.b.mv2 "SCM URL"]
      [:dd.ml0 (or (:scm_url build-info) "nil")]

      [:dt.b.mv2 "Commit SHA"]
      [:dd.ml0 (or (:commit_sha build-info) "nil")]]]))

(defn build-page [build-info]
  (->> [:div.mw8.center.pa2.pv5
        ;; [:pre.pa3 (pr-str build-info)]

        [:span.gray "cljdoc build #" (:id build-info)]
        [:h1.mt2.mb4
         (str (:group_id build-info) "/" (:artifact_id build-info))
         [:span " v" (:version build-info)]]

        (section
         (:analysis_requested_ts build-info)
         [:h3.mt0 "Analysis Requested"])

        (section
         (:analysis_triggered_ts build-info)
         [:h3.mt0 "Analysis Kicked Off"]
         (when-let [job-uri (:analysis_job_uri build-info)]
           [:a.link.blue {:href job-uri} "view job"]))

        (section
         (:analysis_received_ts build-info)
         [:h3.mt0 "Analysis Received"]
         [:a.link.blue
          {:href (if (some-> (:analysis_result_uri build-info) (.startsWith  "/"))
                   (str "file://" (:analysis_result_uri build-info))
                   (:analysis_result_uri build-info))}
          "view result"])

        (section
         (:import_completed_ts build-info)
         [:h3.mt0 "Import Completed"]

         (scm-info build-info)

         [:p (cljdoc-link build-info true)]
         (when (and (:scm_url build-info) (:commit_sha build-info))
           [:p
            [:a.link.blue {:href (:scm_url build-info)}
             [:img.v-mid.mr2 {:src "https://icon.now.sh/github/24"}]
             (subs (:scm_url build-info) 19)]
            " @ "
            [:a.link.blue {:href (str (:scm_url build-info) "/commit/" (:commit_sha build-info))}
             (subs (:commit_sha build-info) 0 8)]]))

        (when-not (:import_completed_ts build-info)
          [:script
           "setTimeout(function(){window.location.reload(1);}, 5000);"])]
       (common/page {:title "cljdoc build #"})))

(defn seconds-diff [d1 d2]
  (let [s (.getSeconds
           (Duration/between
            (Instant/parse d1)
            (Instant/parse d2)))]
    (str "+" s "s")))

(defn builds-page [builds]
  (->> (for [b builds]
         [:div.br2.ba.b--moon-gray.mb2
          [:div.cf.pa3
           [:div.fl
            [:h3.ma0.mb2 (:group_id b) "/" (:artifact_id b) " " (:version b)
             [:a.link.ml2.f5 {:href (str "/builds/" (:id b))}
              [:span.silver.normal "#" (:id b)]]]
            (cljdoc-link b false)]
           [:div.fr
            (when (:import_completed_ts b)
              (if (and (:scm_url b) (:commit_sha b))
                [:span.db.bg-washed-green.pa3.br2 "Good"]
                [:span.db.bg-washed-red.pa3.br2 "SCM info missing"]))]]
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
            (if (:import_completed_ts b)
              (seconds-diff
               (:analysis_requested_ts b)
               (:import_completed_ts b))
              "in progress")]]])
       (into [:div.mw8.center.pv3.ph2
              [:h1 "Recent cljdoc builds"]])

   (common/page {:title "cljdoc builds"})))
