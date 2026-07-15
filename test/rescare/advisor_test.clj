(ns rescare.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [rescare.advisor :as advisor]
            [rescare.store :as store]))

(deftest test-mock-advisor-proposal-shape
  (testing "proposals have required fields"
    (let [adv (advisor/mock-advisor)
          st (store/seed-db)
          req {:op :log-resident-note :resident-id "resident-1" :patch {:mood :happy}}
          proposal (advisor/advise adv st req)]
      (is (:op proposal))
      (is (:resident-id proposal))
      (is (:summary proposal))
      (is (:rationale proposal))
      (is (:cites proposal))
      (is (= :propose (:effect proposal)))
      (is (:value proposal))
      (is (number? (:confidence proposal))))))

(deftest test-all-proposal-types
  (testing "advisor can draft all five op types"
    (let [adv (advisor/mock-advisor)
          st (store/seed-db)
          ops [:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
               :schedule-staff-shift-proposal :flag-safety-concern]]
      (doseq [op ops]
        (let [req {:op op :resident-id "resident-1" :patch {:test :data}}
              proposal (advisor/advise adv st req)]
          (is (= op (:op proposal)))
          (is (= :propose (:effect proposal)))
          (is (>= (:confidence proposal) 0.7)))))))

(deftest test-advisor-confidence-floor
  (testing "confidence values vary by op"
    (let [adv (advisor/mock-advisor)
          st (store/seed-db)]
      (let [note (advisor/advise adv st {:op :log-resident-note :resident-id "resident-1" :patch {}})]
        (is (>= (:confidence note) 0.9)))
      (let [safety (advisor/advise adv st {:op :flag-safety-concern :resident-id "resident-1" :patch {}})]
        (is (< (:confidence safety) 0.85))))))

(deftest test-advisor-never-proposes-direct-action
  (testing "every proposal has :effect :propose"
    (let [adv (advisor/mock-advisor)
          st (store/seed-db)
          ops [:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
               :schedule-staff-shift-proposal :flag-safety-concern]]
      (doseq [op ops]
        (let [proposal (advisor/advise adv st {:op op :resident-id "resident-1" :patch {}})]
          (is (= :propose (:effect proposal))))))))
