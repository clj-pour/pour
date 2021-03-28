# Pour

[![CircleCI](https://circleci.com/gh/clj-pour/pour.svg?style=svg)](https://circleci.com/gh/clj-pour/pour)

Pour consists of a library for applying EQL queries to a value, and a tool for composing queries from functions
annotated with a query (examples at the bottom). The primary use case is for functions that return hiccup.

Pour allows you to define custom resolvers that can be run at arbitrary points inside a query.

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
The user can supply a map of keys to resolver functions on the `resolvers` key on the env which, when present, will be
used preferentially over direct access to the value, as in the example below.

Resolvers are functions that take two arguments - `env` and the current `node` upon which it is operating.
The env can contain arbitrary data, it is up to you. The node provides the value, and the EQL information about that
position in the query, eg params, type..

```clojure
(pour/pour
  {:now       (inst-ms (java.util.Date.))
   :resolvers {:days-ago-timestamp (fn [{:keys [now] :as env} {:keys [value params]
                                                               :as node}]
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

:coming/soon!

```
