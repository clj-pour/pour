(ns pour.core-test
  (:require #?@(:clj  [[clojure.test :refer [deftest testing is]]]
                :cljs [[cljs.test :refer [deftest testing is]]])
            [pour.core :as pour]))

(defrecord Test [a b])

(defn now []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))


(deftest seqy
  (is (not (pour/seqy? {:db/id 123})))
  (is (not (pour/seqy? {})))
  (is (not (pour/seqy? nil)))
  (is (not (pour/seqy? "hi")))
  (is (not (pour/seqy? (->Test 1 2))))
  (is (pour/seqy? []))
  (is (pour/seqy? (list)))
  (is (pour/seqy? (lazy-seq (range 100))))
  (is (pour/seqy? (lazy-cat [] [])))
  (is (pour/seqy? '()))
  (is (pour/seqy? #{})))

(defn union-no-match? [union-key value]
  nil)

(deftest nils
  (testing "missing query should throw"
    (let [!e (atom nil)]
      (try
        (pour/pour nil nil)
        (catch Throwable e
          (reset! !e e)))
      (is (instance? Throwable @!e))
      (is (= (ex-message @!e) "Missing query"))))
  (is (= nil (pour/pour [:a :b] nil)) "nil values are skipped")
  (testing "nil values on provided keys should mean that the key is also not present in the output"
    (let [result (pour/pour [:a] {:a nil})]
      (is (= {} result))))
  (testing "union no match"
    (let [q '[{(:a {:union-dispatch pour.core-test/union-no-match?})
               {:bar [:baz]}}]
          value {:a {:b {:c 1}}}
          result (pour/pour q value)]
      (is (= {} result)
          "Dispatch function never matches and returns nil, empty output")))
  (testing "resolvers that return nil don't close the return channel"
    (let [nil-resolver (fn [_ _]
                         nil)
          env {:resolvers {:test/nil-resolver nil-resolver}}
          q '[:a
              :b
              :c
              :test/nil-resolver]
          v {:a 1 :b 2 :c 3}]
      (is (= (pour/pour env q v)
             {:a 1 :b 2 :c 3})))))

(deftest pour
  (testing "baseline"
    (let [constant-resolver (fn [env node]
                              ::constant)
          v {:bar     1
             :hi      :i-should-be-ignored
             :me      :also
             :another {:thing :gah}
             :other   [{:foo1     :a
                        :not-here :nono}
                       {:foo1     :b
                        :not-here :nono}]}
          q '[:foo
              (:bar {:as :hi})
              {:another [:thing]}
              {:other [:foo1]}]
          env {:resolvers {:foo constant-resolver}}]
      (is (= (pour/pour env q v)
             {:foo     ::constant
              :hi      1
              :another {:thing :gah}
              :other   [{:foo1 :a}
                        {:foo1 :b}]})))))

(deftest empty-seqs
  (testing "via resolver"
    (let [empty-resolver (constantly (list))
          v {}
          q '[{:empty/resolver [:a :b]}]
          env {:resolvers {:empty/resolver empty-resolver}}]
      (is (= {}
             (pour/pour env q v))))))

(defn sleepyresolver
  "Debug resolver that passes through v after a delay"
  [time v]
  (fn [& args]
    (Thread/sleep time)
    #?(:clj (Thread/sleep time)
       :cljs (reduce + (range (* time time))))
   v))

(deftest async
  (testing "values should be resolved in parallel as far as possible"
    (let [d1 (sleepyresolver 25 :d1)
          d2 (sleepyresolver 50 :d2)
          d3 (sleepyresolver 100 :d3)
          d4 (sleepyresolver 30 :d4)
          env {:resolvers {:d1 d1
                           :d2 d2
                           :d3 d3
                           :d4 d4}}
          q '[{:a [:a :b]}
              :d1
              :d2
              :d3
              :d4
              {(:d1 {:as :foo}) [:d1 :d2 :d3 :d4 :should :be :ignored]}]
          start (now)
          _ (is (= (pour/pour env q {:a {:a 1}})
                   {:a   {:a 1},
                    :d1  :d1,
                    :d2  :d2,
                    :d3  :d3,
                    :d4  :d4,
                    :foo {:d1 :d1,
                          :d2 :d2
                          :d3 :d3
                          :d4 :d4}}))
          duration (- (now) start)]
      (is (< duration 250)))))


(deftest pipe
  (testing "pipe resolver should pass through the left hand side to the nested query"
    (let [v {:me "value"}
          q '[{(:pipe {:as :aaa}) [:me]}]]
      (is (= (pour/pour q v)
             {:aaa {:me "value"}})))))


(deftest params
  (testing "as param allows renaming the key"
    (let [root {:name "person"
                :age  30}
          result (pour/pour '[(:name {:as :aliased})]
                            root)]
      (is (= (:aliased result)
             (:name root))))
    (testing "default param should provide a value in the case the resolved value is nil only, passing boolean false through"
      (let [root {:name    "person"
                  :boolean false}
            result (pour/pour '[(:missing {:default 100})
                                (:boolean {:default true})]
                              root)]
        (is (false? (:boolean result)))
        (is (= (:missing result)
               100))))))

(deftest unions
  (let [root {:stuff [{:id     1
                       :record :one}
                      {:id   2
                       :type :two}]}
        result (pour/pour [{:stuff {:record [:record :id]
                                    :type   [:type :id]}}]
                          root)]
    (is (= result
           {:stuff [{:id     1
                     :record :one}
                    {:id   2
                     :type :two}]}))))

(defn custom-dispatch [union-key value]
  (= (:type value) union-key))

(def not-a-function 1)

(deftest union-dispatch
  (testing "dispatch is not a function calls through to on-error, result of on-error function is not used as resolved value"
    (let [errors (atom [])
          root {:routing {:type :a
                          :slug-a "slug-a"}}
          q '[{(:routing {:union-dispatch pour.core-test/not-a-function})
               {:b [:slug-b]
                :c [:slug-c]
                :a [:slug-a]}}]
          result (pour/pour {:on-error #(swap! errors conj %)} q root)]
      (is (= result {}))
      (is (= (.getMessage (first @errors))
             "Union-dispatch reference provided is not a function"))
      (is (= (ex-data (first @errors))
             {:params {:union-dispatch 'pour.core-test/not-a-function}}))))
  (testing "dispatch on a value of a map"
    (let [root {:routing {:type :a
                          :slug-a "slug-a"}}
          q '[{(:routing {:union-dispatch pour.core-test/custom-dispatch})
               {:b [:slug-b]
                :c [:slug-c]
                :a [:slug-a]}}]
          result (pour/pour q root)]
      (is (= result {:routing {:slug-a "slug-a"}}))))
  (testing "allow providing a custom union dispatch function as a parameter"
    (let [root {:stuff [{:type      :one
                         :id        123
                         :product   :book
                         :something "hi"}
                        {:type    :two
                         :id      456
                         :product :book
                         :another "thing"}]}
          result (pour/pour '[{(:stuff {:union-dispatch pour.core-test/custom-dispatch})
                               {:one [:type :something]
                                :two [:type :another]}}]
                            root)]
      (is (= result
             {:stuff [{:type      :one
                       :something "hi"}
                      {:type    :two
                       :another "thing"}]})))))
