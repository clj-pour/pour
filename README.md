# Pour

[![CircleCI](https://circleci.com/gh/dazld/pour.svg?style=svg)](https://circleci.com/gh/dazld/pour)

Declarative, extensible data transformation and composition. This is a distillation and rework of a library used by @project-j. 

## Todo

- improve docs (awful atm, sorry!)
- remove datomic-free dependency (only used to not treat entities as sequences)
- more compose tests
- discuss pulling in more features from parent project at project-j.

## Import

- Currently only available as a git dependency via deps.

## Usage

Pour is based on [EQL](https://github.com/edn-query-language/eql).

There are two arities of the main `pour/pour` function. 

### `query, value`

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

The arity-2 version should be a pretty vanilla implementation of EQL, without too much embellishment.

### `env, query, value`

The Arity-3 version can take an `env` argument in the first position. 

Here the user can specify an environment, or context, in which the query is operating. 
The user can supply a map of `resolvers` which, when present, will be used preferentially over direct access to the value.

Resolvers are functions that take two arguments - `env` and the current `node` upon which it is operating. 
The env can contain arbitrary data, its up to you. The node provide the value, and the EQL information about that position 
in the query, eg params, type..  

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


### Compose

- Tools for composing a query from functions with a `query` metadata property.