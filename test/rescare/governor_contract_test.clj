(ns rescare.governor-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [rescare.store :as store]
            [rescare.advisor :as advisor]
            [rescare.governor :as governor]
            [rescare.phase :as phase]
            [rescare.operation :as operation]))

(deftest test-full-workflow-clean-proposal
  (testing "clean proposal: advisor -> governor -> auto-commit in phase 3"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          req {:op :log-resident-note :resident-id "resident-1" :patch {:mood :happy}}
          result (operation/run-proposal req st adv 3)]
      (is (= :commit (:decision result)))
      (is (= :committed (:execution result))))))

(deftest test-full-workflow-safety-escalates
  (testing "safety concern escalates even in phase 3"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          req {:op :flag-safety-concern :resident-id "resident-1" :patch {:concern :observed-injury}}
          result (operation/run-proposal req st adv 3)]
      (is (= :escalate (:decision result)))
      (is (= :escalated (:execution result))))))

(deftest test-full-workflow-unverified-blocks
  (testing "unverified resident blocks proposal, logs to ledger"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          initial-ledger-count (count (store/ledger st))
          req {:op :log-resident-note :resident-id "resident-3" :patch {}}  ; resident-3 is unverified
          result (operation/run-proposal req st adv 3)]
      (is (= :hold (:decision result)))
      (is (= :held (:execution result)))
      ;; Ledger should record the hold
      (is (> (count (store/ledger st)) initial-ledger-count)))))

(deftest test-full-workflow-phase-gating
  (testing "phase 1 escalates non-notes; phase 3 auto-commits"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          req {:op :schedule-family-or-guardian-visit :resident-id "resident-1" :patch {:date :2026-07-20}}]
      ;; Phase 1: escalate
      (let [result-p1 (operation/run-proposal req st adv 1)]
        (is (= :escalate (:decision result-p1))))
      ;; Phase 3: auto-commit
      (let [result-p3 (operation/run-proposal req st adv 3)]
        (is (= :commit (:decision result-p3)))))))

(deftest test-audit-ledger-records-all-events
  (testing "ledger captures all decision events"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          initial-count (count (store/ledger st))]
      ;; Run a clean proposal
      (operation/run-proposal {:op :log-resident-note :resident-id "resident-1" :patch {}} st adv 3)
      ;; Run an escalation
      (operation/run-proposal {:op :flag-safety-concern :resident-id "resident-1" :patch {}} st adv 3)
      ;; Run a hold
      (operation/run-proposal {:op :log-resident-note :resident-id "resident-3" :patch {}} st adv 3)
      ;; Ledger should have grown
      (is (> (count (store/ledger st)) initial-count)))))

(deftest test-scope-exclusion-blocks-in-workflow
  (testing "scope-excluded proposal holds in full workflow"
    (let [st (store/seed-db)
          ;; Manually craft a scope-excluded proposal
          bad-proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                        :summary "投薬管理を提案" :rationale "medication dosing"
                        :cites [] :value {} :confidence 0.9}
          check-result (governor/check {} :test bad-proposal st)]
      (is (not (:ok? check-result)))
      (is (:hard? check-result)))))

(deftest test-low-confidence-escalates-in-workflow
  (testing "low confidence proposal escalates in phase 3"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          ;; Craft a low-confidence proposal
          low-conf-proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                             :summary "uncertain note" :rationale "maybe"
                             :cites [] :value {} :confidence 0.3}
          check-result (governor/check {} :test low-conf-proposal st)]
      (is (:ok? check-result))
      (is (:escalate? check-result))
      (is (not (:hard? check-result))))))

(deftest test-audit-trail-immutable
  (testing "ledger is append-only, previous events are unchanged"
    (let [st (store/seed-db)
          adv (advisor/mock-advisor)
          ;; Log a fact
          fact1 {:op :test-fact-1}]
      (store/append-ledger! st fact1)
      (let [snapshot1 (vec (store/ledger st))]
        ;; Log another
        (store/append-ledger! st {:op :test-fact-2})
        (let [snapshot2 (vec (store/ledger st))]
          ;; Original should still be at index 0
          (is (= (first snapshot1) (first snapshot2))))))))
