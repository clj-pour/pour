(ns pour.compose
  (:require [clojure.walk :as walk]
            [cljs.analyzer.api :as ana-api]
            [cljs.analyzer :as ana]))

(defn query
  [component]
  (into [(list ::renderer {:default component})]
        (:query (meta component))))

(defn function?
  "Returns true if argument is a function or a symbol that resolves to
  a function (not a macro)."
  [menv x]
  (and (symbol? x) (ana/resolve-var menv x)))

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
                                          (do
                                            (clojure.pprint/pprint {::c (resolve &env query-part)})
                                            (clojure.pprint/pprint {::js (ana-api/resolve &env query-part)})
                                            (if-let [rvar# (ana-api/resolve &env query-part)]
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
                                                query-part)))
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
