(ns pour.compose-test
  (:require [clojure.test :refer [deftest testing is]]
            [pour.compose :refer [defcup render] :as pc]))

(defcup r3
  []
  (fn render [{}]))

(defcup r2
  [{(:pipe {:as :r3}) r3}
   (:other {:default 1})]
  (fn render [{}]))

(defcup r4
  [:a :b]
  (fn [{:keys [a b] :as v}]
    [:div.r4 a b]))

(defcup r1
  [:foo
   :bar
   {(:pipe {:as :r2}) r2}
   {(:pipe {:as :r4}) r4}]
  (fn render [{:keys           [r4]
               {:keys [other]} :r2}]
    [:section
     [:span other]
     ((:pour.compose/renderer r4) r4)]))

(deftest queries
  (let [result (render {}
                       r1
                       {:a 1
                        :b 2})]
    (is (= [:section
            [:span 1]
            [:div.r4 1 2]]
           result))))
(prn (meta r1))

#_(deftest views
    (testing "invalid queries"
      (testing "query is not a vector"
        (let [t (try (eval-in-temp-ns (defcup foo :foo
                                        (fn [])))
                     (catch #?(:clj Throwable :cljs :default) t
                       (-> t ex-cause ex-data)))]
          (is (= 1 (count (:errors t))))
          (is (= :foo (get-in t [:errors 0 :query])))))
      (testing "shadowed queries"
        (let [t (try (eval-in-temp-ns (defcup foo [:a :a]
                                        (fn [])))
                     (catch #?(:clj Throwable :cljs :default) t
                       (-> t ex-cause ex-data)))]
          (is (= 1 (count (:errors t))))
          (is (= {:a 2} (get-in t [:errors 0 :duplicates])))))
      (testing "invalid accessors"
        (let [t (try (eval-in-temp-ns (defcup gensym [#{}]
                                        (fn [])))
                     (catch #?(:clj Throwable :cljs :default) t
                       (-> t ex-cause ex-data)))]
          (is (= 1 (count (:errors t))))
          (is (= (list #{}) (get-in t [:errors 0 :invalid-accessors])))))
      (testing "combined errors"
        (let [t (try (eval-in-temp-ns (defcup foo
                                        [:a :a #{}]
                                        (fn [])))
                     (catch #?(:clj Throwable :cljs :default) t
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
            {::pc/keys [unresolved]
             :keys     [query]} (meta a)]
        (is (fn? a))
        (is (= #{:r2} unresolved))
        (is (= query '[:a
                       (:b {:as :c})
                       {:r2 r2}])))))

(defcup us2
  [:a :b]
  (fn [{:keys [a b]}]
    [:u2 a b]))

(defcup us1
  [{:u1 test/us2}]
  (fn [{:keys [u1]}]
    [:u1 ((::pc/renderer u1) u1)]))

(defcup us3
  [{:u3 test/us1}]
  (fn [{:keys [u3]}]
    [:u3 ((::pc/renderer u3) u3)]))

(deftest unresolved-symbols
  (testing "placeholders that cannot be resolved at runtime throw"
    (let [!err (atom nil)
          result (try (pc/render us1 {})
                      (catch #?(:clj Throwable :cljs :default) e
                        (reset! !err e)))
          err @!err]
      ;(is (instance? #?(:clj Throwable :cljs :default) err))
      (is (= {:query-part 'test/us2}
             (ex-data err)))))
  (testing "nested placeholders resolve correctly"
    (let [result (pc/render {::pc/renderers {:test/us2 us2
                                             :test/us1 us1}}
                            us3
                            {:u3 {:u1 {:a 1 :b 2}}})]
      (is (= [:u3 [:u1 [:u2 1 2]]]
             result))))
  (testing "placeholders are replaced with supplied renderers"
    (let [result (pc/render {::pc/renderers {:test/us2 us2}}
                            us1
                            {:u1 {:a 1 :b 2}})]
      (is (= [:u1 [:u2 1 2]]
             result)))))

(defn custom-dispatch [union-key value]
  (= (:type value) union-key))

(defcup one-renderer
  [:type :something]
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
       (let [renderer (::pc/renderer i)]
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
        result (pc/render {::pc/renderers {:test/two-render two-renderer}} r5 value)]
    (is (= result
           '[:div.r5 ([:div.one-r :one "hi"]
                      [:div.two-r :two "thing"])]))))

(def av "av")
(defcup a1
  [(:foo {:default av})]
  (fn [{:keys [foo] :as d}]
    [:div foo]))


(deftest inlining
  (testing "default values"
    (let [result (pc/render a1 {})]
      (is (= [:div "av"]
             result)))))

