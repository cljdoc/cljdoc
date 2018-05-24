(ns cljdoc.renderers.build-log
  (:require [cljdoc.renderers.common :as common]
            [cljdoc.routes :as routes]))

(defn section [date & contents]
  (when date
    [:div.cf.ba.b--moon-gray.mb2.br1.bg-washed-yellow
     [:div.fl-ns.w-third-ns.bb.bn-ns.b--moon-gray.pa3
      [:span date]]
     (into [:div.fl-ns.w-two-thirds-ns.bg-white.bl-ns.b--moon-gray.pa3] contents)]))

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
             [:dd.ml0 (or (:commit_sha build-info) "nil")]]])
         [:p
          (let [cljdoc-uri (routes/path-for :artifact/version
                                            {:group-id (:group_id build-info)
                                             :artifact-id (:artifact_id build-info)
                                             :version (:version build-info)})]
            [:a.link.blue {:href cljdoc-uri}
             [:img.v-mid.mr2 {:src "https://icon.now.sh/chevron/24"}]
             (str "cljdoc.xyz" cljdoc-uri)])]
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
