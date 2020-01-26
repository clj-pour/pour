(ns pour.core-test
  (:require [clojure.test :refer :all]
            [pour.core :as pour]
            [datomic.api :as d]))

(deftest seqy
  (is (not (pour/seqy? {})))
  (is (not (pour/seqy? nil)))
  (is (not (pour/seqy? "hi")))
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
                 {:a {:a 1},
                  :d1 :d1,
                  :d2 :d2,
                  :d3 :d3,
                  :d4 :d4,
                  :foo {:d1 :d1,
                        :d2 :d2
                        :d3 :d3
                        :d4 :d4}}))
        duration (- (System/currentTimeMillis) start)]
    (is (< duration 250))))


(deftest pipe
  (let [v {:me "value"}
        q '[{(:pipe {:as :aaa}) [:me]}]]
    (is (= (pour/pour q v)
           {:aaa {:me "value"}}))))



(deftest params
  (let [root {:name    "person"
              :age     30
              :address {:street   "10 Acacia Avenue"
                        :city     "Berlin"
                        :postcode "10247"}}
        result (pour/pour '[(:name {:as :alias})
                            (:missing {:default 100})]
                          root)]
    (is (= (:alias result)
           (:name root)))
    (is (= (:missing result)
           100))))

(deftest unions
  (let [root {:stuff [{:record :one}
                      {:type :two}]}
        result (pour/pour [{:stuff {:record [:record]
                                    :type   [:type]}}]
                          root)]
    (is (= result
           {:stuff [{:record :one}
                    {:type :two}]}))))
