(ns examples
  (:require [pour.compose :as pc :refer [defcup]]))

(defcup nav-link
  [:uri :title]
  (fn render [{:keys [uri title]}]
    [:a {:href uri} title]))

(defcup nav
  [{:resolve/app-nav nav-link}]
  (fn render [{:resolve/keys [app-nav]}]
    [:nav
     (for [link app-nav]
       (nav-link link))]))

(defcup app-title
  [:title]
  (fn [{:keys [title]}]
    [:section.main-title
     [:img.logo {:src "/logo.jpg"}]
     [:h1 title]]))

;; Unions are supported

(defcup summary-article
  [:title :author :id]
  (fn [{:keys [title author id]}]
    [:section.article
     [:h3 title " by " author]
     [:a {:href (str "/author/" id)} "Read More"]]))

(defcup summary-feed-item
  [:title
   {:articles summary-article}]
  (fn [{:keys [title articles]}]
    [:Section.summary
     [:h2 title]
     (for [article articles]
       (summary-article article))]))

(defcup video-feed-item
  [:title
   {:media [:src]}]
  (fn [{:keys [title media]}]
    [:section.video
     [:h2 title]
     [:video {:src (:src media)}]]))

(defn matches-type [union-key value]
  (= union-key
     (:type value)))

(defcup feed-renderer
  [:title
   {(:items {:union-dispatch matches-type}) {:video video-feed-item
                                             :summary summary-feed-item}}]
  (fn [{:keys [title items]}]
    [:section.feed
     [:h2 title]
     [:div.items
      (for [item items]
        ;; In this case, we don't know which renderer was matched as part of the
        ;; union resolution, so we lookup the matching renderer in the supplied data
        ;; compose inlines a reference to the matching component under the ::pc/renderer
        ;; key.
        ((::pc/renderer item) item))]]))

(defcup app
  [{(:pipe {:as :title}) app-title}
   {(:pipe {:as :navigation}) nav}
   {:resolve/feed feed-renderer}]
  (fn render [{:resolve/keys [feed]
               :keys [navigation title]}]
    [:section.app
     (nav navigation)
     [:section.more-sections
      (app-title title)]
     (feed-renderer feed)]))

(defn render-stuff []
  (let [root-value {:title "Dynamic Title"}]
    (pc/render {;; env for pour - we provide any resolvers here
                :resolvers {:resolve/app-nav (fn [env node]
                                               ; we're not using anything from the node or env here
                                               ; imagine we're looking up some data in a db, for example.
                                               [{:uri   "/"
                                                 :title "Home"}
                                                {:uri   "/blog"
                                                 :title "Blog"}
                                                {:uri   "/blog/1"
                                                 :title "Article"}])
                            :resolve/feed    (fn [env node]
                                               ;; Hetergenous data, feed is a good example of this
                                               ;; Pour supports dispatch on type for unions
                                               {:title "Your Feed"
                                                :items [{:type  :video
                                                         :title "Video item"
                                                         :media {:src "https://some-url.com/video.mp4"}}
                                                        {:type     :summary
                                                         :title    "From authors you follow"
                                                         :articles [{:id     1
                                                                     :title  "Article 1"
                                                                     :author "Nora"}
                                                                    {:id     2
                                                                     :title  "Article 2"
                                                                     :author "Jim"}
                                                                    {:id     3
                                                                     :title  "Article 3"
                                                                     :author "Lucas"}]}]})}}
               ;; root renderer, where to start
               app
               ;; value to start at
               root-value)))

'[:section.app
  [:nav ([:a {:href "/"} "Home"] [:a {:href "/blog"} "Blog"] [:a {:href "/blog/1"} "Article"])]
  [:section.more-sections [:section.main-title [:img.logo {:src "/logo.jpg"}] [:h1 "Dynamic Title"]]]
  [:section.feed
   [:h2 "Your Feed"]
   [:div.items
    ([:section.video [:h2 "Video item"] [:video {:src "https://some-url.com/video.mp4"}]]
     [:Section.summary
      [:h2 "From authors you follow"]
      ([:section.article [:h3 "Article 1" " by " "Nora"] [:a {:href "/author/1"} "Read More"]]
       [:section.article [:h3 "Article 2" " by " "Jim"] [:a {:href "/author/2"} "Read More"]]
       [:section.article [:h3 "Article 3" " by " "Lucas"] [:a {:href "/author/3"} "Read More"]])])]]]


(comment
  ; output of the renderers
  (render-stuff)
  ; we get some metadata back from the composition giving us the query and which renderer we invoked
  (meta (render-stuff)))
