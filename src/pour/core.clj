(ns pour.core
  (:require [edn-query-language.core :as eql]
            [clojure.core.async :as ca])
  (:import (clojure.lang Seqable)))

(def ^{:private true
       :dynamic true
       :doc     "Results channel for resolvers per invocation of parse"}
  *chan* nil)

(defn seqy? [s]
  (and (not (:db/id s))
       (not (map? s))
       (instance? Seqable s)))

(defn pipe [_ {value :value}]
  value)

(defn knit [{:keys [resolvers] :as env}
            {:keys [dispatch-key value] :as node}]
  (let [resolver (or (get resolvers dispatch-key)
                     (fn default-resolver [_ _]
                       (get value dispatch-key)))]
    (resolver env (merge node {:value value}))))

(defn- matches-union [key value]
  (boolean (key value)))

(declare parse)

(defn process-child [{:keys [env value child node-params]}]
  (let [on-error (or (:on-error env) (fn [t]))
        resolved (try
                   (knit env (merge {:value value} child))
                   (catch Throwable t
                     (on-error t)))
        {:keys [type key params]} child
        result (case type
                 :prop resolved
                 :union (let [union-dispatch (or (when-let [custom-dispatch (:union-dispatch node-params)]
                                                   (and (symbol? custom-dispatch)
                                                        (resolve custom-dispatch)))
                                                 matches-union)]
                          (reduce (fn [_ {:keys [union-key children params] :as uc}]
                                    (when (union-dispatch union-key value)
                                      (reduced (parse env {:value    value
                                                           :children children}))))
                                  {}
                                  (:children child)))
                 :join (if (seqy? resolved)
                         (->> resolved
                              (keep (fn [v]
                                      (parse env (merge {:value v} child))))
                              (into []))
                         (parse env (merge {:value resolved} child)))
                 nil)
        result (if (nil? result)
                 (:default params)
                 result)
        kname (or (:as params) key)]
    (if kname
      [kname result]
      result)))

(defn parse [env {:keys [value children]
                  node-params :params}]
  (when value
    (binding [*chan* (ca/chan)]
      (let [expected-results (count children)
            wrapping? (nil? (get-in children [0 :key]))]
        (doseq [child children]
          (ca/go (ca/>! *chan* (process-child {:env env
                                               :value value
                                               :child child
                                               :node-params node-params}))))
        (ca/<!! (ca/go-loop [result {}
                             pending-results expected-results]
                  (let [out (ca/<! *chan*)
                        next-pending-count (dec pending-results)
                        finished? (zero? next-pending-count)]
                    (if wrapping?
                      out
                      (let [[k v] out
                            next-result (if (nil? v)
                                          result
                                          (assoc result k v))]
                        (if finished?
                          next-result
                          (recur next-result
                                 next-pending-count)))))))))))

(defn pour
  ([query value]
   (pour {} query value))
  ([env query value]
   (let [env (update env :resolvers merge {:pipe pipe})
         ast (eql/query->ast query)]
     (->> {:value value}
          (merge ast)
          (parse env)))))

