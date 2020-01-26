(ns pour.compose-test
  (:require #?@(:clj  [[clojure.test :refer [deftest testing is]]]
                :cljs [[cljs.test :refer [deftest testing is]]])
            [pour.core :as pour]
            [pour.compose :as compose]))

(def r1
  ^{:query '[:foo
             :bar
             {(:pipe {:as :r2}) r2/render}]}
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


(deftest queries
  (let [fetch (partial pour/pour {})
        result (compose/render fetch
                               {:r1        r1
                                :r2/render r2
                                :r3/render r3}
                               :r1
                               {})]
    (is (= [:section [:span :r2/render] [:span 1]]
           result))))
