(ns pour.compose-test
  (:require [clojure.test :refer :all]
            [pour.core :as pour]
            [pour.compose :refer [defcup] :as c]))

(defmacro eval-in-temp-ns [& forms]
  `(binding [*ns* *ns*]
     (in-ns (gensym))
     (clojure.core/use 'clojure.core)
     (clojure.core/use 'pour.compose)
     (eval
       '(do ~@forms))))

(defcup r2
  [{(:pipe {:as :r3}) r3}
   (:other {:default 1})]
  (fn render [r {}]))

(defcup r3
  []
  (fn render [r {}]))


(defcup r1
  [:foo
   :bar
   ;; r2 below is a var, and so the macro will resolve at compile time
   {(:pipe {:as :r2}) r2}
   ;; r4 here is a symbol, so this gets looked up at runtime
   {(:pipe {:as :r4}) r4}]
  (fn render [r {:keys              [r4]
                 {::c/keys [renderer]
                  :keys    [other]} :r2}]
    [:section
     [:span renderer]
     [:span other]
     ((:r4 r) r r4)]))


(defcup r4 [:a :b]
  (fn [r {::c/keys [renderer]
          :keys    [a b] :as v}]
    [:div.r4 a b renderer]))

(deftest views
  (testing "invalid queries"
    (testing "query is not a vector"
      (let [t (try (eval-in-temp-ns (defcup foo :foo
                                      (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 1 (count (:errors t))))
        (is (= :foo (get-in t [:errors 0 :query])))))
    (testing "shadowed queries"
      (let [t (try (eval-in-temp-ns (defcup foo [:a :a]
                                      (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 1 (count (:errors t))))
        (is (= {:a 2} (get-in t [:errors 0 :duplicates])))))
    (testing "invalid accessors"
      (let [t (try (eval-in-temp-ns (defcup gensym [#{}]
                                      (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 1 (count (:errors t))))
        (is (= (list #{}) (get-in t [:errors 0 :invalid-accessors])))))
    (testing "combined errors"
      (let [t (try (eval-in-temp-ns (defcup foo
                                      [:a :a #{}]
                                      (fn [])))
                   (catch Throwable t
                     (-> t ex-cause ex-data)))]
        (is (= 2 (count (:errors t))))
        (is (= (list #{}) (get-in t [:errors 0 :invalid-accessors])))
        (is (= {:a 2} (get-in t [:errors 1 :duplicates]))))))

  (testing "Valid Queries"
    (let [a (eval-in-temp-ns (deref (defcup foo
                                      [:a
                                       (:b {:as :c})
                                       {:r2 r2}]
                                      (fn [{:r2/keys [a]
                                            :keys    [c]}]
                                        [:div a]))))
          {:keys [query]} (meta a)]
      (is (fn? a))
      (is (= query '[:a
                     (:b {:as :c})
                     {:r2 r2}])))))

(deftest queries
  (let [fetch (partial pour/pour {})
        result (c/render fetch
                         {:r1 r1
                          :r2 r2
                          :r3 r3
                          :r4 r4}
                         :r1
                         {:a 1
                          :b 2})]
    (is (= '[(::c/renderer {:default :r1})
             :foo
             :bar
             {(:pipe {:as :r2}) [(::c/renderer {:default :r2})
                                 {(:pipe {:as :r3}) [(::c/renderer {:default :r3})]}
                                 (:other {:default 1})]}
             {(:pipe {:as :r4}) [(::c/renderer {:default :r4})
                                 :a
                                 :b]}]
           (:query (meta result))))

    (is (= [:section
            [:span :r2]
            [:span 1]
            [:div.r4 1 2 :r4]]
           result))))
