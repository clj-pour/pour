(ns pour.core
  (:require [edn-query-language.core :as eql]))

(defn seqy? [s]
  (and (not (nil? s))
       (not (:db/id s))
       (not (map? s))
       (not (string? s))
       (seqable? s)))


(defn pipe [_ {value :value}]
  value)

(defn knit [{:keys [resolvers] :as env}
            {:keys [dispatch-key value] :as node}]
  (let [operate (fn []
                  (let [resolver (or (get resolvers dispatch-key)
                                     (fn default-resolver [_ _]
                                       (get value dispatch-key)))]
                    (resolver env (merge node {:value value}))))]
    #?(:clj  (future (operate))
       :cljs (operate))))

(defn- matches-union [key value]
  (boolean (key value)))

(defn- process-map [m]
  (if (map? m)
    (->> m
         (keep (fn [[k v]]
                   (when (not (nil? v))
                     [k v])))
         (into {}))
    m))

(declare parse)

(defn process-union [node-params env value child]
  (let [custom-dispatch (:union-dispatch node-params)
        resolved-custom-dispatcher (when (symbol? custom-dispatch)
                                     (apply resolve [custom-dispatch]))
        union-dispatch (or resolved-custom-dispatcher
                           matches-union)]
    (reduce (fn [_ {:keys [union-key children params] :as uc}]
              (when (union-dispatch union-key value)
                (reduced (parse env {:value    value
                                     :children children}))))
            {}
            (:children child))))

(defn parse [env {:keys [value children] :as node}]
  (when value
    (let [node-params (:params node)
          extract-value #?(:clj deref
                           :cljs identity)]
      (->> children
           (map (fn [child]
                  {:pending (knit env (merge {:value value} child))
                   :child   child}))
           (reduce (fn [acc {:keys [pending child]}]
                     (let [resolved (extract-value pending)
                           {:keys [type key params]} child
                           v (condp = type
                               :prop resolved
                               :union (process-union node-params env value child)
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
           process-map))))


(defn pour
  ([query value]
   (pour {} query value))
  ([env query value]
   (let [env (update env :resolvers merge {:pipe pipe})
         ast (eql/query->ast query)]
     (->> {:value value}
          (merge ast)
          (parse env)))))

