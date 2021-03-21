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

(defcup r3
  []
  (fn render [{}]))

(defcup r2
  [{(:pipe {:as :r3}) r3}
   (:other {:default 1})]
  (fn render [{}]))

(defcup r4
  [:a :b]
  (fn [{::c/keys [renderer]
        :keys    [a b] :as v}]
    [:div.r4 a b renderer]))

(defcup r1
  [:foo
   :bar
   {(:pipe {:as :r2}) r2}
   {(:pipe {:as :r4}) r4}]
  (fn render [{:keys              [r4]
               {::c/keys [renderer]
                :keys    [other]} :r2}]
    [:section
     [:span renderer]
     [:span other]
     ((::c/render-fn r4) r4)]))



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

(defn custom-dispatch [union-key value]
  (= (:type value) union-key))

(defcup one-renderer
  [:type :something {:foo test/bla}]
  (fn [{:keys [type something]}]
    [:div.one-r type something]))

(defcup two-renderer
  [:type :another]
  (fn [{:keys [type another]}]
    [:div.two-r type another]))

(defcup r5
  [{(:stuff {:union-dispatch custom-dispatch}) {;;direct reference, query is inlined
                                                :one one-renderer
                                                ;; defined at runtime
                                                :two test/two-render}}]
  (fn [{:as d}]
    [:div.r5
     (for [i (:stuff d)]
       (let [renderer (::c/render-fn i)]
         (renderer i)))]))

(deftest unions
  (let [value {:stuff [{:type      :one
                        :id        123
                        :product   :book
                        :something "hi"}
                       {:type    :two
                        :id      456
                        :product :book
                        :another "thing"}]}
        result '(c/render2 r5 value)]
    (clojure.pprint/pprint (meta r5))
    (is (= '[:div.r5 ([:div.one-r :one "hi"]
                      [:div.two-r :two "thing"])]
           result))))


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
    (is (= [:section
            [:span ::r2]
            [:span 1]
            [:div.r4 1 2 ::r4]]
           result))))
