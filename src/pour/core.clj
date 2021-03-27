(ns pour.core
  (:require [edn-query-language.core :as eql]
            [clojure.core.async :as ca])
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

(defn process-child [{:keys [chan env value child node-params]}]
  (ca/go (ca/>! chan (let [on-error (or (:on-error env) (fn [t] (prn ::error t)))
                           resolved (try
                                      (knit env (merge {:value value} child))
                                      (catch Throwable t
                                        (on-error t)
                                        nil))
                           {:keys [type key params]} child
                           result (case type
                                    :prop resolved
                                    :union (let [union-dispatch (or (when-let [custom-dispatch (:union-dispatch node-params)]
                                                                      (or (and (fn? custom-dispatch) custom-dispatch)
                                                                          (and (var? custom-dispatch) @custom-dispatch)
                                                                          (and (symbol? custom-dispatch)
                                                                               (some-> custom-dispatch resolve deref))))
                                                                    matches-union)]
                                             (if-not (fn? union-dispatch)
                                               (do (on-error (ex-info "Union-dispatch reference provided is not a function" {:params node-params}))
                                                   nil)
                                               (->> child
                                                    :children
                                                    (reduce (fn [default {:keys [union-key children params] :as uc}]
                                                              (if (union-dispatch union-key value)
                                                                (reduced (parse env {:value    value
                                                                                     :children children}))
                                                                default))
                                                            (ca/go ::nil))
                                                    (ca/<!))))
                                    :join (if (seqy? resolved)
                                            (if (empty? resolved)
                                              nil
                                              (->> resolved
                                                   (map (fn [v]
                                                          (parse env (merge {:value v} child))))
                                                   (ca/map (fn [& args]
                                                             args))
                                                   (ca/<!)
                                                   (into [])))

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
                           result))))))

(defn parse [env {:keys       [value children key]
                  node-params :params}]
  (if (nil? value)
    (ca/go nil)
    (let [chan (ca/chan)
          expected-results (count children)
          wrapping? (nil? (get-in children [0 :key]))]
      (doseq [child children]
        (process-child {:env         env
                        :chan        chan
                        :value       value
                        :child       child
                        :node-params node-params}))

      (ca/go-loop [result {}
                   pending-results expected-results]
        (let [next-pending-count (dec pending-results)
              finished? (zero? next-pending-count)
              next-result (let [out (ca/<! chan)]
                            ;; change to ::nil sentinel
                            (if wrapping?
                              (if (= ::nil out) nil out)
                              (let [[k v] out]
                                (if (nil? v)
                                  result
                                  (assoc result k v)))))]
          (if finished?
            next-result
            (recur next-result
                   next-pending-count)))))))

(defn pour
  ([query value]
   (pour {} query value))
  ([env query value]
   (let [env (update env :resolvers merge {:pipe pipe})
         ast (eql/query->ast query)]
     (if-not query
       (throw (ex-info "Missing query" {:env env :query query}))
       (->> {:value value}
            (merge ast)
            (parse env)
            (ca/<!!))))))


