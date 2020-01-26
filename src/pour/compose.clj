(ns pour.compose
  (:require [clojure.walk :as walk]
            [loom.graph :as g]
            [loom.alg :as alg]))

(defn query
  ([component]
   (:query (meta component)))
  ([kw component]
   (into [(list :renderer {:default kw})]
         (query component))))

(defn queries [renderers]
  (->> renderers
       (map (fn [[k v]] [k (query k v)]))
       (into {})))

(defn find-vars [query]
  (filter symbol? (tree-seq coll? seq query)))

(defn dep-map [config]
  (zipmap (keys config)
          (map #(set (filter symbol? (find-vars (query %))))
               (vals config))))

(defn dep-order [config]
  (-> config dep-map g/digraph alg/topsort reverse))

(defn inject-query [queries query]
  (walk/prewalk (fn [query-part]
                  (if (symbol? query-part)
                    (get queries (keyword query-part) query-part)
                    query-part))
                query))

(defn resolve-all-deps [queries]
  (reduce (fn [queries' k]
            (update queries' k #(inject-query queries %)))
          queries
          (dep-order queries)))

(defn render
  "for a given map of `renderers`, invoke the renderer `kw` with root value `root-value`
  using the supplied `fetch` function"
  [fetch renderers root-renderer root-value]
  (let [queries (-> renderers queries resolve-all-deps)
        renderer (root-renderer renderers)
        query (root-renderer queries)]
    (with-meta (or (->> (fetch query root-value)
                        (renderer renderers))
                   {})
               {:renderer renderer
                :query    query})))

