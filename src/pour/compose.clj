(ns pour.compose
  (:require [clojure.walk :as walk]
            [loom.graph :as g]
            [loom.alg :as alg]
            [clojure.set :as set]))

(defn query
  ([component]
   (or (::query component)
       (:query (meta component))))
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

(defn validate-query [q]
  (let [invalid-idents (remove (fn [i]
                                 (or (keyword? i)
                                     (list? i)
                                     (map? i)))
                               q)
        idents (keep (fn [v]
                       (cond
                         (keyword? v) v
                         (list? v) (or (:as (second v))
                                       (first v))
                         (map? v) (first (keys v))))
                     q)
        freqs (frequencies idents)]
    (cond-> []
            (seq invalid-idents)
            (conj {::error      "invalid query "
                   ::bad-idents invalid-idents})
            (not (every? (fn [[k v]]
                           (= 1 v))
                         freqs))
            (conj {::error      "duplicate identifiers found in query"
                   ::duplicates (into {} (keep (fn [[k v]] (when (> v 1) [k v])) freqs))})
            (not (vector? q))
            (conj {::error "query not a vector"
                   ::query q}))))


(defmacro view
  "View component"
  [query body]
  (let [query-map# (first (next &form))
        fn# (quote body)
        query-errors# (validate-query query-map#)]
    (prn ::f (first (second body)))
    (when-let [errors query-errors#]
      (prn ::invalid-query query-map# errors)
      (throw (ex-info "nuhuh" {:error :bad})))
    (prn ::qm query-map#)
    `{::query (quote ~query)
      ::fn    ~body}))


(defn render
  "for a given map of `renderers`, invoke the renderer `root-renderer` with root value `root-value`
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

