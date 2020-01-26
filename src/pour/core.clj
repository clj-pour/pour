(ns pour.core
  (:require [edn-query-language.core :as eql])
  (:import (clojure.lang Seqable)
           (datomic Entity)))

(defn seqy? [s]
  (and (not (instance? Entity s))
       (not (map? s))
       (instance? Seqable s)))

(defn pipe [_ {value :value}]
  value)

(defn knit [{:keys [resolvers] :as env}
            {:keys [dispatch-key value] :as node}]
  (future (let [resolver (or (get resolvers dispatch-key)
                             (fn default-resolver [_ _]
                               (get value dispatch-key)))]
            (resolver env (merge node {:value value})))))

(defn- matches-union [key value]
  (boolean (key value)))

(defn- process-map [m]
  (if (map? m)
    (->> m
         (keep (fn [[k v]]
                 (let [vv (if (future? v)
                            (deref v)
                            v)]
                   (when (not (nil? vv))
                     [k vv]))))
         (into {}))
    m))

(defn parse [env {:keys [value children]}]
  (when value
    (->> children
         (map (fn [child]
                {:pending (knit env (merge {:value value} child))
                 :child child}))
         (reduce (fn [acc {:keys [pending child]}]
                   (let [resolved (deref pending)
                         {:keys [type key params]} child
                         v (condp = type
                             :prop resolved
                             :union (reduce (fn [_ {:keys [union-key children] :as uc}]
                                              (when (matches-union union-key value)
                                                (reduced (parse env {:value    value
                                                                     :children children}))))
                                            {}
                                            (:children child))
                             :join (if (seqy? resolved)
                                     (->> resolved
                                          (keep (fn [v]
                                                  (parse env (merge {:value v} child))))
                                          (into []))
                                     (parse env (merge {:value resolved} child)))
                             nil)
                         result (or v (:default params))
                         kname (or (:as params) key)]
                     (if (not (nil? result))
                       (if kname
                         (assoc acc kname result)
                         result)
                       acc)))
                 {})
         process-map)))


(defn pour
  ([query value]
   (pour {} query value))
  ([env query value]
   (let [env (update env :resolvers merge {:pipe pipe})
         ast (eql/query->ast query)]
     (->> {:value value}
          (merge ast)
          (parse env)))))

