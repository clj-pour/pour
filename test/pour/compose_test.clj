(ns pour.compose-test
  (:require [clojure.test :refer :all]
            [pour.core :as pour]
            [pour.compose :refer [view] :as compose]))

(defmacro eval-in-temp-ns [& forms]
  `(binding [*ns* *ns*]
     (in-ns (gensym))
     (clojure.core/use 'clojure.core)
     (clojure.core/use 'pour.compose)
     (eval
       '(do ~@forms))))

(def r1
  ^{:query '[:foo
             :bar
             {(:pipe {:as :r2}) r2}
             {(:pipe {:as :r4}) r4}]}
  (fn render [r {:keys [r4]
                 {:keys [renderer other]} :r2}]
    [:section
     [:span renderer]
     [:span other]
     ((:r4 r) r r4)]))

(def r2
  ^{:query '[(:other {:default 1})]}
  (fn render [r {}]))

(def r3
  ^{:query '[]}
  (fn render [r {}]))

(def r4 (view [:a :b]
              (fn [r {:keys [a b renderer] :as v}]
                [:div.r4 a b renderer])))


(deftest views
  (testing "invalid queries"
    (testing "query is not a vector"
      (let [t (try (eval-in-temp-ns (view :foo
                                          (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 1 (count (:errors t))))
        (is (= :foo (get-in t [:errors 0 :query])))))
    (testing "shadowed queries"
      (let [t (try (eval-in-temp-ns (view [:a :a]
                                          (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 1 (count (:errors t))))
        (is (= {:a 2} (get-in t [:errors 0 :duplicates])))))
    (testing "invalid accessors"
      (let [t (try (eval-in-temp-ns (view [#{}]
                                          (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 1 (count (:errors t))))
        (is (= (list #{}) (get-in t [:errors 0 :invalid-accessors])))))
    (testing "combined errors"
      (let [t (try (eval-in-temp-ns (view [:a :a #{}]
                                          (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 2 (count (:errors t))))
        (is (= (list #{}) (get-in t [:errors 0 :invalid-accessors])))
        (is (= {:a 2} (get-in t [:errors 1 :duplicates]))))))

  (testing "Valid Queries"
    (let [a (eval-in-temp-ns (view [:a
                                    (:b {:as :c})
                                    {:r2 r2}]
                                   (fn [{:r2/keys [a]
                                         :keys    [b]}]
                                     [:div a])))
          {:keys [query]} (meta a)]
      (is (fn? a))
      (is (= query '[:a
                     (:b {:as :c})
                     {:r2 r2}])))))

(deftest queries
  (let [fetch (partial pour/pour {})
        result (compose/render fetch
                               {:r1 r1
                                :r2 r2
                                :r3 r3
                                :r4 r4}
                               :r1
                               {:a 1
                                :b 2})]
    (is (= '[(:renderer {:default :r1})
             :foo
             :bar
             {(:pipe {:as :r2}) [(:renderer {:default :r2})
                                 (:other {:default 1})]}
             {(:pipe {:as :r4}) [(:renderer {:default :r4})
                                 :a
                                 :b]}]
            (:query (meta result))))

    (is (= [:section
            [:span :r2]
            [:span 1]
            [:div.r4 1 2 :r4]]
           result))))
