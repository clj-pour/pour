(ns pour.compose
  (:require [clojure.walk :as walk]
            [pour.core :as pour]))

(defn query
  [component]
  (into [(list ::renderer {:default component})]
        (:query (meta component))))

(defn inject-query [renderers unprocessed-query]
  (walk/prewalk (fn [query-part]
                  (if (symbol? query-part)
                    (let [component (get renderers (keyword query-part))
                          {raw-query :query} (meta component)]
                      (if raw-query
                        (query component)
                        (throw (ex-info (str "Missing renderer for " query-part)
                                        {:query-part query-part}))))
                    query-part))
                unprocessed-query))

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
                                            (let [;; pick up unresolved renderers from children
                                                  qp-unresolveds# (::unresolved (meta (deref rvar#)))
                                                  mq# (:query (meta (deref rvar#)))]
                                              ;; merge into accumulated unresolveds at this level
                                              (when (seq qp-unresolveds#)
                                                (swap! !unresolved-symbols# into qp-unresolveds#))
                                              (if mq#
                                                (query (deref rvar#))
                                                (deref rvar#)))
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

(defn render
  ([renderer value]
   (render {} renderer value))
  ([env renderer value]
   (let [m (meta renderer)
         unresolveds (::unresolved m)
         renderers (::renderers env)
         query (cond->> (:query m)
                        (seq unresolveds) (inject-query renderers))]
     (with-meta (->> (pour/pour (or env {})
                                query
                                value)
                     (renderer))
                {:renderer renderer
                 :query    query}))))

