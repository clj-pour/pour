(ns pour.core
  (:require [edn-query-language.core :as eql]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log])
  (:import (clojure.lang Seqable)))

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
  (log/info ::pc value)
  (let [on-error (or (:on-error env) (fn [t] (log/error t)))
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
                                      (reduced (ca/<! (parse env {:value    value
                                                                  :children children})))))
                                  {}
                                  (:children child)))
                 :join (if (seqy? resolved)
                         (->> resolved
                              (keep (fn [v]
                                      (ca/<! (parse env (merge {:value v} child)))))
                              (into []))
                         (ca/<! (parse env (merge {:value resolved} child))))
                 nil)
        result (if (nil? result)
                 (:default params)
                 result)
        kname (or (:as params) key)]
    (if kname
      [kname result]
      ;; change to ::nil
      (if (nil? result)
        ::nil
        result))))

(defn parse [env {:keys       [value children key]
                  node-params :params}]
  (prn ::k key)
  (when value
    (binding []
      (let [chan (ca/chan)
            expected-results (count children)
            wrapping? (nil? (get-in children [0 :key]))]
        (doseq [child children]
          (ca/go (ca/>! chan (process-child {:env         env
                                             :value       value
                                             :child       child
                                             :node-params node-params}))))

        (ca/go-loop [result {}
                     pending-results expected-results]
          (let [next-pending-count (dec pending-results)
                finished? (zero? next-pending-count)

                next-result (try
                              (let [out (ca/<! chan)
                                    _ (log/info ::out out)]
                                ;; change to ::nil sentinel
                                (if wrapping?
                                  (if (= ::nil out) nil out)
                                  (let [[k v] out]
                                    (if (nil? v)
                                      result
                                      (assoc result k v)))))
                              (catch Throwable t
                                (log/error ::uhoh t)
                                result))]
            (if finished?
              next-result
              (recur next-result
                     next-pending-count))))))))

(defn pour
  ([query value]
   (pour {} query value))
  ([env query value]
   (prn ::hi)
   (let [env (update env :resolvers merge {:pipe pipe})
         ast (eql/query->ast query)]
     (->> {:value value}
          (merge ast)
          (parse env)
          (ca/<!!)))))

