(ns examples
  (:require [pour.compose :as compose :refer [defcup]]))

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

(defcup app
  [{(:pipe {:as :title}) app-title}
   {(:pipe {:as :navigation}) nav}]
  (fn render [{:keys [navigation title] :as v}]
    [:section.app
     (nav navigation)
     [:section.more-sections
      (app-title title)]]))

(defn render-stuff []
  (let [root-value {:title "Dynamic Title"}
        resolvers {:resolve/app-nav (fn [env node]
                                      ; we're not using anything from the node or env here
                                      ; imagine we're looking up some data in a db, for example.
                                      [{:uri   "/"
                                        :title "Home"}
                                       {:uri   "/blog"
                                        :title "Blog"}
                                       {:uri   "/blog/1"
                                        :title "Article"}])}]
    (compose/render {:resolvers resolvers} ;; env for pour - we provide any resolvers here
                    app ;; root renderer, where to start
                    root-value))) ;; value to start at

'[:section.app
  [:nav ([:a {:href "/"} "Home"]
         [:a {:href "/blog"} "Blog"]
         [:a {:href "/blog/1"} "Article"])]
  [:section.more-sections
   [:section.main-title
    [:img.logo {:src "/logo.jpg"}]
    [:h1 "Dynamic Title"]]]]

(comment
  ; output of the renderers
  (render-stuff)
  ; we get some metadata back from the composition giving us the query and which renderer we invoked
  (meta (render-stuff)))
