(ns examples
  (:require [pour.core :as pour]
            [pour.compose :as compose]))

(def link
  ; The metadata below defines the keys which the renderer requires
  ; In this case, we're just providing a vector of keys, so no need to quote the query
  ^{:query [:uri :title]}
  ; The args to the render function itself are as follows:
  ; r - this is a map of all the renderers provided at the root. we use this to lookup
  ; any nested renderers and invoke them, after the data has been resolved
  ; data - this is the value of the data resolved by pour at this level.
  (fn render [r {:keys [uri title]}]
    [:a {:href uri} title]))

(def nav
  ; In the query below we provide a symbol, meaning that compose will try to look up
  ; that symbol as a keyword in the provided renderers, and inline the query associated
  ; with that renderer at this point in the query, before any data is resolved. That query
  ; is joined onto the data provided by the left hand side (in this case, the data provided by
  ; the `:app-nav` resolver, which is a collection of links) at data fetch time.
  ; it's not currently possibly to inline a query without joining it on something.
  ; As we're using symbols, we have to quote the query now.
  ^{:query '[{:app-nav atom/link}]}
  ; For this component, we've declared a dependency on the `atom/link` component above,
  ; so we will have to look it up in the supplied `r` map of renderers to invoke it
  ; with the data previously resolved.
  ; we don't actually know anything about what this component needs, beyond that we're joining
  ; onto the values provided by :app-nav, all we do is declare that we're using this, and that
  ; we'll be invoking it.
  (fn render [r {:keys [app-nav]}]
    [:nav
     (for [link app-nav]
       ; This is a bit tortuous, but we look up the function and then invoke it with the renderers
       ; map and the data.
       ((:atom/link r) r link))]))

(def title
  ^{:query [:title]}
  (fn [_ {:keys [title]}]
    [:section.main-title
     [:img.logo {:src "/logo.jpg"}]
     [:h1 title]]))

(def app
  ; As above, but beyond using symbols, we're also using parameters, meaning that again we have to
  ; quote the query, or the list syntax used for parameters would be interpreted as a function call
  ; again, we have to join the components onto something, so in this case we're just passing through
  ; the root value.
  ; normally, you'd be joining onto some value from the root, or a resolver of some sort.
  ^{:query '[{(:pipe {:as :title}) atom/title}
             {(:pipe {:as :navigation}) organism/nav}]}
  (fn render [r {:keys [navigation title]}]
    [:section.app
     ((:organism/nav r) r navigation)
     [:section.more-sections
      ((:atom/title r) r title)
      [:h2 "Heading 2"]]]))

(defn renderers []
  ; could be automated as you wish!
  ; naming of keys is up to the user
  {:atom/link    link
   :atom/title   title
   :organism/nav nav
   :template/app app})

(defn render-stuff []
  (let [root-value {:title "Dynamic Title"}
        resolvers {:app-nav (fn [env node]
                              ; we're not using anything from the node or env here
                              ; imagine we're looking up some data in a db, for example.
                              [{:uri   "/"
                                :title "Home"}
                               {:uri   "/blog"
                                :title "Blog"}
                               {:uri   "/blog/1"
                                :title "Article"}])}]
    (compose/render ; fetch data function, partially applied with an environment
                    (partial pour/pour {:resolvers resolvers})

                    ; all the renderers we're providing
                    (renderers)

                    ; where to start, a root
                    :template/app

                    ; the value which we join the harvested query from the root above
                    root-value)))


; Given all the plumbing above, we end up with a dynamically created query something like this:
[(:renderer {:default :template/app})
 {(:pipe {:as :title}) [(:renderer {:default :atom/title}) :title]}
 {(:pipe {:as :navigation}) [(:renderer {:default :organism/nav})
                             {:app-nav [(:renderer {:default :atom/link}) :uri :title]}]}]



(comment
  ; output of the renderers
  (render-stuff)
  ; we get some metadata back from the composition giving us the query and which renderer we invoked
  (meta (render-stuff)))
