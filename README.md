# Pour

[![CircleCI](https://circleci.com/gh/clj-pour/pour.svg?style=svg)](https://circleci.com/gh/clj-pour/pour)

## A nice strong cup of EQL.

For a given function, and any nested calls, how do we know what data we need to satisfy that tree of functions?
Can we ask the functions to tell us, and figure that out before we even call those functions?

In a very specific way, `pour` tries to answer yes to those questions. Imagine `select-keys` where the first argument
can be EQL, and the keys can either be accessors to a property on the value, or an invocation of a resolver on the value
at that point. Further, imagine being able to build the EQL query up from the functions themselves, so that the
description of the data a function needs is colocated with the function itself.

Pour consists of a library for applying EQL queries to a value, and a tool for composing queries from functions
annotated with a query (examples at the bottom), with the primary use case being functions that return hiccup (or
similar).

It currently only supports JVM clojure but cljs support is under development.

Resolution is run inside core async processes, and runs in parallel as far as possible.

- available as a git dependency via deps, eg:

```
{:deps {pour {:git/url "https://github.com/clj-pour/pour.git"
              :sha     "99547c2c17de2856a10f6e3c4f227582d9e4f230"}}}
```

## Usage

Pour is based on [EQL](https://github.com/edn-query-language/eql).

There are two arities of the main `pour/pour` function.

### `query, value`

The arity-2 version is a mostly barebones implementation of extracting data from a map with EQL.

Apply the query to the provided value, eg:

```clojure
(require '[pour.core :as pour])

(pour/pour [:a :b] {:a 1 :b 2 :c 3})
=> {:a 1 :b 2}
```

If a requested key is not present in the value, it is not present in the output.

```clojure
(pour/pour [:b] {:a 1 :c 3})
=> {}
```

### `env, query, value`

The Arity-3 version can take an `env` argument in the first position.

Here the user can specify an environment, or context, in which the query is operating.
The user can supply a map of keys to resolver functions on the `resolvers` key on the env which, when present, will be used preferentially over direct access to the value,
as in the example below.

Resolvers are functions that take two arguments - `env` and the current `node` upon which it is operating.
The env can contain arbitrary data, it is up to you. The node provides the value, and the EQL information about that
position in the query, eg params, type..

```clojure
(pour/pour
  {:now       (inst-ms (java.util.Date.))
   :resolvers {:days-ago-timestamp (fn [{:keys [now] :as env} {:keys [value params]
                                                               :as ast-node}]
                                     (let [days-ago (:days-ago params)
                                           days-ago-ms (* days-ago 24 60 60 1000)]
                                       (- now days-ago-ms)))}}

  '[(:days-ago-timestamp {:days-ago 7})
    :foo]
  {:foo :bar})
```


## Compose Usage

Tool for composing and executing `defcup` components together. Pour knows nothing about what the output might
be - that's up to you - but we'll be building hiccup in the examples below.

```clojure

(ns example
  (:require [pour.core :as pour]
            [pour.compose :as pc :refer [defcup]]))

(defcup link
  ; The metadata below defines the keys which the renderer requires
  [:uri :title]
  ; data - this is the value of the data resolved by pour at this level.
  (fn render [{:keys [uri title]}]
    [:a {:href uri} title]))

(defcup nav
  ; In the query below we provide a symbol, meaning that compose will try to look up
  ; that symbol as a keyword in the provided renderers, and inline the query associated
  ; with that renderer at this point in the query, before any data is resolved. That query
  ; is joined onto the data provided by the left hand side (in this case, the data provided by
  ; the `:app-nav` resolver, which is a collection of links) at data fetch time.
  ; it's not currently possibly to inline a query without joining it on something.
  ; As we're using symbols, we have to quote the query now.
  [{:app-nav link}]}
  ; For this component, we've declared a dependency on the `link` component above,
  ; we don't actually know anything about what this component needs, beyond that we're joining
  ; onto the values provided by :app-nav, all we do is declare that we're using this, and that
  ; we'll be invoking it.
  (fn render [{:keys [app-nav]}]
    [:nav
     (for [nav-link app-nav]
       ;; The renderer is also available on the ::pc/renderer key of the resolved data as a function
       (link nav-link))]))

(defcup title
  [:title]
  (fn [{:keys [title]}]
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
    (pc/render ; fetch data function, partially applied with an environment
                    (partial pour/pour {:resolvers resolvers})

                    ; where to start, a root
                    app
                    ; the value which we join the harvested query from the root above
                    root-value)))


; Given all the plumbing above, we end up with a dynamically created query something like this:
[{(:pipe {:as :title})
  [:title]}
 {(:pipe {:as :navigation})
  [{:app-nav
    [:uri :title]}]}]


```

The examples are in the [dev/examples](https://github.com/clj-pour/pour/blob/master/dev/examples.clj) folder.

PRs are welcome.
