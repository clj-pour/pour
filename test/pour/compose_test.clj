(ns pour.compose-test
  (:require [clojure.test :refer :all]
            [pour.core :as pour]
            [pour.compose :refer [view] :as compose]))

(def r1
  ^{:query '[:foo
             :bar
             {(:pipe {:as :r2}) r2}]}
  (fn render [r {{:keys [renderer other]} :r2}]
    [:section
     [:span renderer]
     [:span other]]))

(def r2
  ^{:query '[(:other {:default 1})]}
  (fn render [r {}]))

(def r3
  ^{:query '[]}
  (fn render [r {}]))

(deftest views
  (let [a (view [:a
                 :c
                 []
                 #{}
                 map
                 #inst "2021"
                 (:b {:as :c})
                 {:r2 r2}]
                (fn [{:r2/keys [a]
                      :keys    [b]}]
                  [:div a]))]
    (is (= #{::compose/query
             ::compose/fn}
           (set (keys a))))))

(deftest queries
  (let [fetch (partial pour/pour {})
        result (compose/render fetch
                               {:r1 r1
                                :r2 r2
                                :r3 r3}
                               :r1
                               {})]
    (is (= [:section [:span :r2/render] [:span 1]]
           result))))
