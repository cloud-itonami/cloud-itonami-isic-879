(ns rescare.sim
  "Demo driver / simulation for the ISIC-879 residential care actor.
  Walks through happy-path scenarios and hard-check failures to verify
  the actor + governor work end-to-end."
  (:require [rescare.store :as store]
            [rescare.advisor :as advisor]
            [rescare.governor :as governor]
            [rescare.phase :as phase]
            [rescare.operation :as operation]))

;; ----------------------------- demo scenarios -----------------------------

(defn- print-sep []
  (println (str (apply str (repeat 70 "=")))))

(defn demo-happy-path
  "Happy path: clean proposals on verified residents auto-commit in phase 3."
  [st adv]
  (println "\n--- Scenario: Happy Path (Phase 3 auto-commit) ---")
  (let [req {:op :log-resident-note :resident-id "resident-1" :patch {:mood :happy :activity :reading}}
        result (operation/run-proposal req st adv 3)]
    (println (str "Proposal: " (pr-str (:proposal result))))
    (println (str "Decision: " (:decision result)))
    (println (str "Execution: " (:execution result)))
    (assert (= :commit (:decision result)) "Expected auto-commit in phase 3")
    (assert (= :committed (:execution result)) "Expected execution: committed"))
  (print-sep))

(defn demo-phase-gating
  "Phase 1 blocks non-notes; phase 3 permits all (except safety)."
  [st adv]
  (println "\n--- Scenario: Phase Gating (visit in phase 1 vs phase 3) ---")

  ;; Phase 1: visit should escalate
  (let [req {:op :schedule-family-or-guardian-visit :resident-id "resident-2" :patch {:visitor :guardian :date :2026-07-20}}
        result (operation/run-proposal req st adv 1)]
    (println (str "Phase 1, visit op: decision=" (:decision result)))
    (assert (= :escalate (:decision result)) "Expected escalate in phase 1 for visit"))

  ;; Phase 3: visit should auto-commit
  (let [req {:op :schedule-family-or-guardian-visit :resident-id "resident-2" :patch {:visitor :guardian :date :2026-07-20}}
        result (operation/run-proposal req st adv 3)]
    (println (str "Phase 3, visit op: decision=" (:decision result)))
    (assert (= :commit (:decision result)) "Expected auto-commit in phase 3 for visit"))

  (print-sep))

(defn demo-unverified-resident
  "HARD check: unverified resident blocks all proposals."
  [st adv]
  (println "\n--- Scenario: HARD Check - Unverified Resident ---")
  (let [req {:op :log-resident-note :resident-id "resident-3" :patch {:mood :quiet}}
        result (operation/run-proposal req st adv 3)]
    (println (str "Target: resident-3 (registered but unverified)"))
    (println (str "Decision: " (:decision result)))
    (println (str "Reason: " (:reason result)))
    (assert (= :hold (:decision result)) "Expected hold for unverified resident")
    (assert (seq (:violations (:check-result result))) "Expected violations recorded"))
  (print-sep))

(defn demo-non-propose-effect
  "HARD check: `:effect` must be `:propose`."
  [st adv]
  (println "\n--- Scenario: HARD Check - Non-:propose Effect ---")
  ;; Manually craft a malformed proposal (advisor wouldn't do this)
  (let [bad-proposal {:op :log-resident-note :resident-id "resident-1" :effect :commit
                      :summary "Bad" :rationale "test" :cites [] :value {} :confidence 0.8}
        check-result (governor/check {} :test bad-proposal st)]
    (println (str "Proposal effect: " (:effect bad-proposal)))
    (println (str "Check ok?: " (:ok? check-result)))
    (println (str "Violations: " (mapv :rule (:violations check-result))))
    (assert (not (:ok? check-result)) "Expected check to fail")
    (assert (some #(= :effect-not-propose (:rule %)) (:violations check-result))
            "Expected effect-not-propose violation"))
  (print-sep))

(defn demo-scope-exclusion
  "HARD check: proposals mentioning medication/clinical/restraint/etc. are blocked."
  [st adv]
  (println "\n--- Scenario: HARD Check - Scope Exclusion ---")
  (let [bad-proposal (governor/out-of-scope-test-proposal st {:resident-id "resident-1"})
        check-result (governor/out-of-scope-test-check bad-proposal st)]
    (println (str "Proposal summary: " (:summary bad-proposal)))
    (println (str "Check ok?: " (:ok? check-result)))
    (println (str "Violations: " (mapv :rule (:violations check-result))))
    (assert (not (:ok? check-result)) "Expected check to fail")
    (assert (some #(= :scope-excluded (:rule %)) (:violations check-result))
            "Expected scope-excluded violation"))
  (print-sep))

(defn demo-safety-always-escalates
  "Safety concerns always escalate, regardless of phase or confidence."
  [st adv]
  (println "\n--- Scenario: Safety Concerns Always Escalate ---")
  (let [req {:op :flag-safety-concern :resident-id "resident-1" :patch {:concern :witnessed-fall}}
        result (operation/run-proposal req st adv 3)]  ; even phase 3
    (println (str "Operation: flag-safety-concern"))
    (println (str "Phase: 3 (would auto-commit other ops)"))
    (println (str "Decision: " (:decision result)))
    (assert (= :escalate (:decision result)) "Expected escalate for safety concern even in phase 3"))
  (print-sep))

(defn demo-low-confidence-escalates
  "Low confidence proposals escalate (soft check, not HARD)."
  [st adv]
  (println "\n--- Scenario: Low Confidence Escalates ---")
  ;; Manually craft a low-confidence proposal
  (let [low-conf-proposal {:op :log-resident-note :resident-id "resident-1" :effect :propose
                           :summary "Maybe note" :rationale "unclear" :cites [] :value {} :confidence 0.4}
        check-result (governor/check {} :prod low-conf-proposal st)]
    (println (str "Proposal confidence: " (:confidence low-conf-proposal)))
    (println (str "Confidence floor: " governor/confidence-floor))
    (println (str "Escalate?: " (:escalate? check-result)))
    (assert (:escalate? check-result) "Expected escalate for low confidence")
    (assert (:ok? check-result) "Low confidence is soft check (ok=true, escalate=true)"))
  (print-sep))

(defn run-demo []
  "Execute all demo scenarios."
  (println "\n╔═══════════════════════════════════════════════════════════════════════╗")
  (println "║     rescare (ISIC-879) Residential Care Actor Demo                    ║")
  (println "╚═══════════════════════════════════════════════════════════════════════╝")

  (let [st (store/seed-db)
        adv (advisor/mock-advisor)]

    (println "\n--- Initial State ---")
    (println (str "Residents: " (map :name (store/all-residents st))))
    (println (str "Phase operations: Phase 0=" (phase/phase-table 0) ", Phase 3=" (phase/phase-table 3)))
    (print-sep)

    ;; Run scenarios
    (demo-happy-path st adv)
    (demo-phase-gating st adv)
    (demo-unverified-resident st adv)
    (demo-non-propose-effect st adv)
    (demo-scope-exclusion st adv)
    (demo-safety-always-escalates st adv)
    (demo-low-confidence-escalates st adv)

    ;; Audit log
    (println "\n--- Audit Ledger ---")
    (let [ledger (store/ledger st)]
      (println (str "Total events: " (count ledger)))
      (doseq [evt ledger]
        (println (str "  " (:op evt)))))))

;; ----------------------------- entry point for clojure -M:run -----------------------------

(defn -main [& _args]
  (try
    (run-demo)
    (println "\n✓ All scenarios completed successfully!")
    (catch #?(:clj Exception :cljs js/Error) e
      (println (str "\n✗ Demo failed: " (ex-message e)))
      (throw e))))

;; For REPL use:
(comment
  (run-demo))
