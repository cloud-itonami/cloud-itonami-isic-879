(ns rescare.advisor
  "ResCareAdvisor -- the *contained intelligence node* for the
  ISIC-879 residential-care operations-coordination actor.

  It drafts exactly five kinds of back-office proposal from a closed
  allowlist: resident-note logging, family/guardian visit scheduling, supply
  coordination, staff shift proposals, and safety-concern flagging.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record
  and NEVER a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by `rescare.governor`
  before anything touches the SSoT.

  This advisor NEVER drafts medication decisions, clinical assessment,
  care-plan changes, physical restraint use, guardianship/custody decisions,
  disciplinary actions, end-of-life decisions, or safety-authority actions
  -- those are permanently out of scope for this actor, not merely
  un-implemented. `rescare.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode
  (a compromised or confused advisor drifting into scope it must never touch)
  and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :resident-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-resident-note
  "Draft a daily resident-note log entry. Pure logging of observed care
  (meals, mood, activity, hygiene) -- never a clinical assessment."
  [_db {:keys [resident-id patch]}]
  {:op         :log-resident-note
   :resident-id resident-id
   :summary    (str resident-id " の日常的活動ノートを記録: " (pr-str (keys patch)))
   :rationale  "入居者の日常的な活動・食事・気分・衛生の観察記録のみ。臨床判断なし。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.94})

(defn- propose-family-visit
  "Draft a family/guardian visit scheduling proposal (a calendar
  entry, never a direct dispatch). Adapted for both family visitors
  and appointed guardians (for residents in state custody or care)."
  [_db {:keys [resident-id patch]}]
  {:op         :schedule-family-or-guardian-visit
   :resident-id resident-id
   :summary    (str resident-id " の家族・保護者面会予定を提案: " (pr-str (keys patch)))
   :rationale  "入居者と家族・保護者の面会時間調整のみ。面会実施の決定は家族・保護者が行う。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.89})

(defn- propose-supply-request
  "Draft a consumable supply request coordination (linens, mobility aids,
  food stock, clothing, hygiene supplies -- never medication or clinical supplies)."
  [_db {:keys [resident-id patch]}]
  {:op         :coordinate-supply-request
   :resident-id resident-id
   :summary    (str resident-id " に関連する消耗品リクエスト: " (pr-str (keys patch)))
   :rationale  "布製品・移動補助具・食料品・衣類などの非医薬品消耗品の調達調整のみ。投薬なし。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.91})

(defn- propose-staff-shift
  "Draft a staff-shift roster PROPOSAL only (never a binding assignment).
  Actual shift finalization is always done by shift supervisors."
  [_db {:keys [resident-id patch]}]
  {:op         :schedule-staff-shift-proposal
   :resident-id resident-id
   :summary    (str resident-id " のケア担当者シフト提案: " (pr-str (keys patch)))
   :rationale  "ケアスタッフのシフト割り当て提案のみ。確定は人間の シフト管理者が判断する。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.87})

(defn- propose-safety-concern
  "Surface a resident/facility safety concern (falls, wellbeing incidents,
  observed distress, child protection concerns) for HUMAN triage. This op
  ALWAYS escalates in `rescare.governor` -- never auto-committed at any
  phase -- regardless of how confident the advisor is that the concern is real."
  [_db {:keys [resident-id patch]}]
  {:op         :flag-safety-concern
   :resident-id resident-id
   :summary    (str resident-id " に関する安全懸念フラグ: " (pr-str (keys patch)))
   :rationale  "入居者の転落・福祉・安全上の懸念事項の報告。常に人間審査が必須。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.75})

;; ----------------------------- MockAdvisor (deterministic for tests) -----------------------------

(defrecord MockAdvisor []
  Advisor
  (-advise [_ _db request]
    (let [{:keys [op resident-id]} request]
      (case op
        :log-resident-note (propose-resident-note _db request)
        :schedule-family-or-guardian-visit (propose-family-visit _db request)
        :coordinate-supply-request (propose-supply-request _db request)
        :schedule-staff-shift-proposal (propose-staff-shift _db request)
        :flag-safety-concern (propose-safety-concern _db request)
        ;; Unknown op -- return a rejection-shaped proposal that will be caught by governor
        {:op op :resident-id resident-id :effect :propose :confidence 0.0
         :summary "未認識の操作" :rationale "この操作型は許可リストに含まれない"
         :cites [] :value {}}))))

(defn mock-advisor []
  (->MockAdvisor))

(defn advise
  "Primary entry point to the advisor. Mock implementation."
  [advisor store request]
  (-advise advisor store request))
