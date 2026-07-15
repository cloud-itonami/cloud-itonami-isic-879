(ns rescare.store-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [rescare.store :as store]))

(deftest test-mem-store-protocol
  (testing "MemStore satisfies Store protocol"
    (let [st (store/mem-store {"r1" {:resident-id "r1" :name "Alice" :registered? true :verified? true}})]
      (is (satisfies? store/Store st)))))

(deftest test-resident-lookup
  (testing "resident returns the correct record"
    (let [alice {:resident-id "r1" :name "Alice" :registered? true :verified? true}
          st (store/mem-store {"r1" alice})]
      (is (= alice (store/resident st "r1")))))
  (testing "resident returns nil for unknown id"
    (let [st (store/mem-store {})]
      (is (nil? (store/resident st "unknown"))))))

(deftest test-all-residents
  (testing "all-residents returns sorted list"
    (let [r1 {:resident-id "a" :name "Alice" :registered? true :verified? true}
          r2 {:resident-id "b" :name "Bob" :registered? true :verified? true}
          st (store/mem-store {"b" r2 "a" r1})]
      (is (= [r1 r2] (store/all-residents st))))))

(deftest test-ledger-operations
  (testing "append-ledger! adds facts to immutable log"
    (let [st (store/mem-store {})]
      (store/append-ledger! st {:op :test-fact :value "data"})
      (store/append-ledger! st {:op :test-fact-2 :value "more"})
      (is (= 2 (count (store/ledger st)))))))

(deftest test-commit-record
  (testing "commit-record! appends to coordination-log"
    (let [st (store/mem-store {})]
      (let [record {:resident-id "r1" :proposal {:op :log-resident-note}}]
        (store/commit-record! st record)
        (is (= [record] (store/coordination-log st)))))))

(deftest test-with-residents
  (testing "with-residents seeds/replaces the directory"
    (let [st (store/mem-store {})
          residents {"r1" {:resident-id "r1" :name "Alice" :registered? true :verified? true}}]
      (store/with-residents st residents)
      (is (= residents (into {} (map #(vector (:resident-id %) %) (store/all-residents st))))))))
