(ns rescare.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [rescare.phase :as phase]))

(deftest test-phase-0-read-only
  (testing "phase 0 auto-commits nothing"
    (is (empty? (phase/phase-table 0)))
    (is (not (phase/can-auto-commit? :log-resident-note 0)))
    (is (not (phase/can-auto-commit? :schedule-family-or-guardian-visit 0)))))

(deftest test-phase-1-notes-only
  (testing "phase 1 auto-commits only resident notes"
    (is (contains? (phase/phase-table 1) :log-resident-note))
    (is (phase/can-auto-commit? :log-resident-note 1))
    (is (not (phase/can-auto-commit? :schedule-family-or-guardian-visit 1)))
    (is (not (phase/can-auto-commit? :coordinate-supply-request 1)))))

(deftest test-phase-2-expanded
  (testing "phase 2 auto-commits notes, visits, supply, shifts (not safety)"
    (let [auto (phase/phase-table 2)]
      (is (contains? auto :log-resident-note))
      (is (contains? auto :schedule-family-or-guardian-visit))
      (is (contains? auto :coordinate-supply-request))
      (is (contains? auto :schedule-staff-shift-proposal))
      (is (not (contains? auto :flag-safety-concern))))))

(deftest test-phase-3-full-except-safety
  (testing "phase 3 auto-commits all four non-safety ops"
    (let [auto (phase/phase-table 3)]
      (is (contains? auto :log-resident-note))
      (is (contains? auto :schedule-family-or-guardian-visit))
      (is (contains? auto :coordinate-supply-request))
      (is (contains? auto :schedule-staff-shift-proposal))
      (is (not (contains? auto :flag-safety-concern))))))

(deftest test-safety-never-auto-commits
  (testing "flag-safety-concern never auto-commits at any phase"
    (is (not (phase/can-auto-commit? :flag-safety-concern 0)))
    (is (not (phase/can-auto-commit? :flag-safety-concern 1)))
    (is (not (phase/can-auto-commit? :flag-safety-concern 2)))
    (is (not (phase/can-auto-commit? :flag-safety-concern 3)))))

(deftest test-phase-descriptions
  (testing "phase descriptions are available"
    (is (string? (phase/describe-phase 0)))
    (is (string? (phase/describe-phase 1)))
    (is (string? (phase/describe-phase 3)))
    (is (.contains (phase/describe-phase 0) "read-only"))))
