(ns pour.compose
  (:require [clojure.walk :as walk]
            [loom.graph :as g]
            [loom.alg :as alg]
            [pour.core :as pour]))

(defn query
  ([component]
   (:query (meta component)))
  ([kw component]
   (into [(list ::renderer {:default kw})
          (list ::render-fn {:default component})]
         (query component))))

(defn queries [renderers]
  (->> renderers
       (map (fn [[k v]] [k (query k v)]))
       (into {})))

(defn find-vars [query]
  (filter symbol? (tree-seq coll? seq query)))

(defn dep-map [config]
  (zipmap (keys config)
          (map #(set (find-vars (query %)))
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
  (if-not (vector? q)
    [{:error "query not a vector"
      :query q}]
    (let [invalid-accessors (remove (fn [i]
                                      (or (keyword? i)
                                          (list? i)
                                          (map? i)))
                                    q)
          join-errors (reduce (fn [acc v]
                                (if-not (map? v)
                                  acc
                                  (if (vector? (second (first v)))
                                    (into acc (validate-query (second (first v))))
                                    acc)))

                              []
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
              (seq join-errors)
              (into join-errors)

              (seq invalid-accessors)
              (conj {:error             "Query contains bad accessors"
                     :invalid-accessors invalid-accessors})

              (not (every? (fn [[k v]]
                             (= 1 v))
                           freqs))
              (conj {:error      "duplicate identifiers found in query"
                     :query      q
                     :duplicates (into {} (keep (fn [[k v]] (when (> v 1) [k v])) freqs))})))))

(defmacro defcup
  "Define a cup to pour."
  [cup-name query-literal body]
  (let [!unresolved-symbols# (atom #{})
        resolved-query# (walk/prewalk (fn [query-part]
                                        (if (symbol? query-part)
                                          (if-let [rvar# (resolve &env query-part)]
                                            (let [kw# (keyword (symbol rvar#))
                                                  ;; pick up unresolved renderers from children
                                                  qp-unresolveds# (::unresolved (meta (deref rvar#)))
                                                  mq# (:query (meta (deref rvar#)))]
                                              ;; merge into accumulated unresolveds at this level
                                              (when (seq qp-unresolveds#)
                                                (swap! !unresolved-symbols# into qp-unresolveds#))
                                              (if mq#
                                                (query kw# (deref rvar#))
                                                rvar#))
                                            (do
                                              ;; the passed symbol doesn't correspond to anything we can resolve
                                              ;; hence, we will expect it to be supplied at runtime
                                              (swap! !unresolved-symbols# conj (keyword query-part))
                                              query-part))
                                          query-part))
                                      query-literal)
        query-errors# (validate-query resolved-query#)
        unresolveds# @!unresolved-symbols#]
    (when (seq query-errors#)
      (throw (ex-info "Query Error" {:type   ::query-error
                                     :errors query-errors#})))
    `(def ~cup-name
       (with-meta ~body
                  (merge (meta ~body)
                         {::unresolved '~unresolveds#
                          :query       '~resolved-query#})))))

(defn render2
  ([renderer value]
   (render2 {} renderer value))
  ([env renderer value]
   (let [m (meta renderer)
         query (:query m)
         unresolveds (::unresolved m)
         renderers (::renderers env)]
         ;; zip up the unresolveds with supplied renderers and error if something missing
     (with-meta (->> (pour/pour (or env {})
                                query
                                value)
                     (renderer))
                {:renderer renderer
                 :query    query}))))

(defn render
  "for a given map of `renderers`, invoke the renderer `root-renderer` with root value `root-value`
  using the supplied `fetch` function"
  [fetch renderers root-renderer root-value]
  (let [queries (-> renderers queries resolve-all-deps)
        renderer (if (fn? root-renderer)
                   root-renderer
                   (root-renderer renderers))
        query (root-renderer queries)]
    (with-meta (or (->> (fetch query root-value)
                        (renderer))
                   {})
               {:renderer renderer
                :query    query})))

