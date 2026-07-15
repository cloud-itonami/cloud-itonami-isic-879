(ns rescare.phase
  "Phase 0→3 rollout for the ISIC-879 residential-care operations actor.
  Each phase defines the scope and automation level of proposals that
  may auto-commit (without human approval), vs. those that must escalate.

  The phases are:
    Phase 0: read-only -- no proposals commit, all escalate (bootstrap/audit)
    Phase 1: resident-notes only -- log-resident-note auto-commits if clean
    Phase 2: add visit + supply + shifts -- family-visit, supply, shift auto-commit
    Phase 3: full except safety -- all four non-safety ops auto-commit; safety always escalates

  At every phase, `:flag-safety-concern` ALWAYS escalates to a human,
  never auto-commits. This is a structural guarantee -- two layers
  (phase table + governor) enforce the same invariant.")

(defn phase-table
  "The rollout table: phase -> set of ops that may auto-commit (if clean).
  Safety concerns always escalate at every phase."
  [phase-num]
  (case phase-num
    0 #{}                    ; read-only
    1 #{:log-resident-note}
    2 #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request :schedule-staff-shift-proposal}
    3 #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request :schedule-staff-shift-proposal}
    #{}))  ; default: read-only

(defn can-auto-commit?
  "Given a proposal op and phase number, returns true if the op
  is permitted to auto-commit in this phase (given governor clean).
  `:flag-safety-concern` is NEVER auto-committed at any phase."
  [op phase-num]
  (and (not= op :flag-safety-concern)
       (contains? (phase-table phase-num) op)))

(defn describe-phase
  "Human-readable description of each phase."
  [phase-num]
  (case phase-num
    0 "Phase 0 (read-only): all proposals escalate"
    1 "Phase 1: resident-notes auto-commit; others escalate"
    2 "Phase 2: notes, visits, supply, shifts auto-commit; safety escalates"
    3 "Phase 3 (full ops): all four non-safety ops auto-commit; safety always escalates"
    (str "Phase " phase-num " (unknown)")))
