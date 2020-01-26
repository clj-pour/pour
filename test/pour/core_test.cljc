(ns pour.core-test
  (:require #?@(:clj  [[clojure.test :refer [deftest testing is]]
                       [datomic.api :as d]]
                :cljs [[cljs.test :refer [deftest testing is]]])
            [pour.core :as pour]))

(defrecord Test [a b])

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

(deftest datomic-entities
  (testing "Datomic entities should not be treated as sequences"
    (let [uri "datomic:mem://pour-test"
          _ (d/create-database uri)
          conn (d/connect uri)]
      (is (not (pour/seqy? (d/entity (d/db conn) :db/ident)))))))

(deftest nils
  (testing "nil values on provided keys should mean that the key is also not present in the output"
    (let [result (pour/pour [:a] {:a nil})]
      (is (= {} result)))))

(deftest pour
  (let [constant-resolver (fn [env node]
                            :resolved-with-constant)
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
           {:foo     :resolved-with-constant
            :hi      1
            :another {:thing :gah}
            :other   [{:foo1 :a}
                      {:foo1 :b}]}))))

(defn sleepyresolver
  "Debug resolver that passes through v after a delay"
  [time v]
  (fn [& args]
    (Thread/sleep time)
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
          start (System/currentTimeMillis)
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
          duration (- (System/currentTimeMillis) start)]
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
    (testing "default param should provide a value in the case the resolved value is nil"
      (let [root {:name "person"}
            result (pour/pour '[(:missing {:default 100})]
                              root)]
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

(deftest union-dispatch
  (testing "allow providing a custom union dispatch function as a parameter"
    (let [root {:stuff [{:type :one
                         :something "hi"}
                        {:type :two
                         :another "thing"}]}
          result (pour/pour '[{(:stuff {:union-dispatch pour.core-test/custom-dispatch}) {:one   [:type
                                                                                                  :something]
                                                                                          :two   [:type
                                                                                                  :another]}}]
                            root)]
      (is (= result
             {:stuff [{:type :one
                       :something "hi"}
                      {:type :two
                       :another "thing"}]})))))
