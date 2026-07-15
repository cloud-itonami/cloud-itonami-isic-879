(ns rescare.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [rescare.governor :as governor]
            [rescare.store :as store]
            [rescare.advisor :as advisor]))

(deftest test-resident-unverified-violation
  (testing "unverified resident blocks any proposal"
    (let [st (store/mem-store {"resident-1" {:resident-id "resident-1" :name "Test" :registered? true :verified? false}})
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose :summary "test" :rationale "" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :resident-unverified (:rule %)) (:violations result)))
      (is (:hard? result))))
  (testing "unregistered resident blocks any proposal"
    (let [st (store/mem-store {})  ; empty store
          proposal {:op :log-resident-note :resident-id "unknown" :effect :propose :summary "test" :rationale "" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (:hard? result)))))

(deftest test-effect-not-propose-violation
  (testing ":effect must be :propose"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :commit :summary "test" :rationale "" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :effect-not-propose (:rule %)) (:violations result)))
      (is (:hard? result)))))

(deftest test-scope-exclusion-violations
  (testing "medication term triggers scope exclusion"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "投薬管理を提案" :rationale "medications" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :scope-excluded (:rule %)) (:violations result)))
      (is (:hard? result))))
  (testing "clinical-diagnosis term triggers scope exclusion"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "clinical diagnosis" :rationale "test" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (:hard? result))))
  (testing "restraint term triggers scope exclusion"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "physical restraint" :rationale "test" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (:hard? result))))
  (testing "guardianship term in proposal triggers scope exclusion"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "test" :rationale "guardianship decision" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (:hard? result)))))

(deftest test-disallowed-op
  (testing "op outside allowlist triggers scope exclusion"
    (let [st (store/seed-db)
          proposal {:op :unknown-op :resident-id "resident-1" :effect :propose :summary "test" :rationale "" :cites [] :value {} :confidence 0.9}
          result (governor/check {} :test proposal st)]
      (is (not (:ok? result)))
      (is (some #(= :op-not-allowed (:rule %)) (:violations result)))
      (is (:hard? result)))))

(deftest test-low-confidence-escalates-softly
  (testing "low confidence escalates but is not a hard block"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose :summary "test" :rationale "" :cites [] :value {} :confidence 0.4}
          result (governor/check {} :test proposal st)]
      (is (:ok? result))  ; soft check, not hard
      (is (:escalate? result))
      (is (not (:hard? result))))))

(deftest test-safety-concern-always-escalates
  (testing "safety concerns escalate even if clean and high confidence"
    (let [st (store/seed-db)
          proposal {:op :flag-safety-concern :resident-id "resident-1" :effect :propose :summary "witnessed fall" :rationale "test" :cites [] :value {} :confidence 0.99}
          result (governor/check {} :test proposal st)]
      (is (:ok? result))
      (is (:escalate? result))
      (is (:high-stakes? result)))))

(deftest test-legitimate-safety-concern-not-blocked
  (testing "safety concern with legitimate observation is not scope-excluded"
    (let [st (store/seed-db)
          ;; A legitimate safety observation about difficulty swallowing should NOT
          ;; be caught by the scope exclusion for 'medication'
          proposal {:op :flag-safety-concern :resident-id "resident-1" :effect :propose
                    :summary "resident reports difficulty swallowing" :rationale "observation during meals"
                    :cites ["resident-1"] :value {} :confidence 0.85}
          result (governor/check {} :test proposal st)]
      ;; Should escalate (safety always escalates) but not be HARD-held by scope exclusion
      (is (:ok? result))
      (is (:escalate? result))
      (is (not (some #(= :scope-excluded (:rule %)) (:violations result)))))))

(deftest test-clean-proposal-ok
  (testing "clean, verified proposal passes all checks"
    (let [st (store/seed-db)
          proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                    :summary "morning mood observation" :rationale "routine logging"
                    :cites ["resident-1"] :value {:mood :content} :confidence 0.95}
          result (governor/check {} :test proposal st)]
      (is (:ok? result))
      (is (not (:hard? result))))))
