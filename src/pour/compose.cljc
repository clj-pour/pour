(ns pour.compose
  (:require [clojure.walk :as walk]
            [pour.core :as pour])
 (:require-macros [pour.compose :refer [defcup]]))

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



#_(defmacro eval-in-temp-ns [& forms]
    `(binding [*ns* *ns*]
       (in-ns (gensym))
       (clojure.core/use 'clojure.core)
       (clojure.core/use 'pour.compose)
       (eval
         '(do ~@forms))))

(defcup r2 [:a] (fn []))

(defcup r1 [{:a r2}] (fn []))

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

