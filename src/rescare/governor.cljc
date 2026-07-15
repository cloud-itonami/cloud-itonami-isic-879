(ns rescare.governor
  "ResCareGovernor -- the independent compliance layer that earns
  the ResCareAdvisor the right to commit. The advisor has no notion
  of whether a resident is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation instead
  of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (resident-note logging, family/guardian visit scheduling, supply
  coordination, staff shift proposals, safety-concern flagging).
  It NEVER performs or authorizes:
    - medication administration or dosing
    - clinical diagnosis or assessment
    - care-plan changes
    - physical restraint use decisions
    - guardianship or custody decisions
    - disciplinary actions or behavioral management decisions
    - end-of-life or DNR decisions
    - safety-authority overrides (complaint investigation, license
      enforcement, compliance actions)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Resident unverified      -- the target resident record must
                                   exist AND be independently
                                   confirmed `:registered?`/
                                   `:verified?` in the store before
                                   ANY proposal for it may commit or
                                   even escalate. Never trusts a
                                   proposal's own claim about the
                                   resident -- re-derived from the
                                   resident's own store record, the same
                                   'ground truth, not self-report'
                                   discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST
                                   be `:propose`. Any other effect
                                   value is, by construction, a
                                   claim to directly actuate/commit
                                   outside governance -- HARD block,
                                   not merely low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   medication/clinical-diagnosis/
                                   care-plan/restraint/guardianship/
                                   disciplinary/end-of-life/
                                   safety-authority territory is a
                                   HARD, PERMANENT block -- this
                                   actor's charter excludes that
                                   territory structurally, not as a
                                   rollout milestone. Evaluated
                                   UNCONDITIONALLY on every
                                   proposal. An op outside the
                                   closed five-op allowlist is the
                                   SAME failure mode (an advisor
                                   proposing something it was never
                                   authorized to propose) and is
                                   folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-safety-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `rescare.phase` independently agrees: `:flag-safety-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one."
  (:require [clojure.string :as str]
            [rescare.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
    :schedule-staff-shift-proposal :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- medication, clinical
  decision-making, physical restraint, guardianship, disciplinary action,
  end-of-life decisions, or safety-authority enforcement. Scanned across
  the proposal's op/summary/rationale/cites/value, never trusting the
  advisor's own framing of its intent.

  Carefully qualified (e.g. 'medication dosing' not bare 'medic') so
  legitimate safety-concern observations (e.g. 'resident observed
  difficulty swallowing') are never incorrectly blocked -- the governor's
  scope scan must never collide with the actor's core valid use case."
  ["medic" "薬" "medication" "dosing" "処方" "prescription"
   "clinical diagnosis" "clinical-diagnosis" "臨床診断" "assessment"
   "care plan" "care-plan" "ケアプラン" "treatment plan" "care coordination change"
   "physical restraint" "physical-restraint" "身体拘束" "restraint" "拘束"
   "guardianship" "custody" "legal-custody" "state custody" "親権" "後見"
   "disciplinary" "discipline" "punishment" "behavioural management" "懲罰"
   "end of life" "end-of-life" "dnr" "do not resuscitate" "終末期"
   "safety authority" "safety-authority" "safety enforcement" "license suspension" "license-suspension"
   "compliance enforcement" "compliance-enforcement" "investigat" "complaint" "違反"])

;; ----------------------------- checks -----------------------------

(defn- resident-unverified-violations
  "The target resident must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:resident-id` claim without a store lookup."
  [{:keys [resident-id]} st]
  (let [r (store/resident st resident-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :resident-unverified
        :detail (str resident-id " は未登録または未検証の入居者 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches medication/clinical/restraint/guardianship/
  disciplinary/end-of-life/safety-authority territory, regardless of
  confidence or how clean every other check is. Evaluated UNCONDITIONALLY
  on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "投薬/臨床判断/ケアプラン変更/身体拘束/親権・後見/懲罰/終末期判断/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a ResCareAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [resident-id (or (:resident-id proposal) (:resident-id request))
        hard (into []
                   (concat (resident-unverified-violations {:resident-id resident-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        op (:op proposal)
        escalate? (or (seq hard) (< conf confidence-floor) (contains? always-escalate-ops op))]
    {:ok? (not (seq hard))
     :violations hard
     :confidence conf
     :escalate? escalate?
     :high-stakes? (or (seq hard) (contains? always-escalate-ops op))
     :hard? (seq hard)}))

;; Minimal contract test hook for exercising scope exclusion

(defn- out-of-scope-test-proposal
  "Deliberately draft an out-of-scope proposal to exercise the scope-exclusion
  gate in testing. NOT called in production. In demo, this helps verify the
  governor's HARD block works even when the advisor is confused."
  [_db {:keys [resident-id]}]
  {:op         :log-resident-note
   :resident-id resident-id
   :summary    "投薬管理の提案"  ; DELIBERATELY includes "投薬" (medication)
   :rationale  "この提案は故意にスコープ外の内容を含む - ガバナーテストのみ用"
   :cites      [resident-id]
   :effect     :propose
   :value      {:resident-id resident-id :medication-change :increased}
   :confidence 0.95})

(defn out-of-scope-test-check
  "Helper to verify the governor HARD-blocks an intentionally
  scope-excluded proposal. Used only in test suites."
  [proposal store]
  (check {} :test-context proposal store))
