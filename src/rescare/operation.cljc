(ns rescare.operation
  "The langgraph-clj StateGraph orchestrating a single proposal workflow:
  intake → advise → govern → decide → {commit | hold | escalate}.

  One run = one proposal, no unbounded internal loops. Human sign-off
  is coordinated via `interrupt-before` checkpoints. The graph state is
  append-only in the store's audit ledger."
  (:require [rescare.store :as store]
            [rescare.advisor :as advisor]
            [rescare.governor :as governor]
            [rescare.phase :as phase]))

;; ----------------------------- workflow state -----------------------------

(defn intake-state
  "Initial state for a proposal request."
  [request]
  {:request request
   :proposal nil
   :check-result nil
   :decision :pending
   :reason nil})

;; ----------------------------- workflow nodes -----------------------------

(defn intake-node
  "Entry point: validate request shape."
  [state store _ctx]
  (let [request (:request state)
        required [:op :resident-id]]
    (if (every? #(contains? request %) required)
      state
      (assoc state :decision :hold :reason "Invalid request: missing required fields"))))

(defn advise-node
  "Call the advisor to draft a proposal."
  [state store advisor-impl _ctx]
  (let [request (:request state)
        proposal (advisor/advise advisor-impl store request)]
    (assoc state :proposal proposal)))

(defn govern-node
  "Apply governor checks to the proposal."
  [state store _ctx]
  (let [request (:request state)
        proposal (:proposal state)
        check-result (governor/check request :production proposal store)]
    (assoc state :check-result check-result)))

(defn decide-node
  "Decide: commit (auto), escalate (human), or hold (error)."
  [state store phase-num _ctx]
  (let [check-result (:check-result state)
        proposal (:proposal state)
        op (:op proposal)
        hard-violations? (:hard? check-result)
        escalate? (:escalate? check-result)]
    (cond
      hard-violations?
      (assoc state :decision :hold :reason "Governor HARD check failed")

      escalate?
      (assoc state :decision :escalate :reason "Requires human approval")

      (phase/can-auto-commit? op phase-num)
      (assoc state :decision :commit :reason "Clean + phase permits auto-commit")

      :else
      (assoc state :decision :escalate :reason "Phase does not permit auto-commit"))))

(defn commit-node
  "Record the decision to the store."
  [state store _ctx]
  (let [decision (:decision state)]
    (if (= decision :commit)
      (let [proposal (:proposal state)
            record {:proposal proposal :decision :committed :timestamp (ex/get-time-ms)}]
        (store/commit-record! store record)
        (store/append-ledger! store {:op :proposal-committed :record record})
        (assoc state :execution :committed))
      state)))

(defn escalate-node
  "Log escalation to human (no auto-action)."
  [state store _ctx]
  (let [decision (:decision state)]
    (if (= decision :escalate)
      (let [proposal (:proposal state)
            fact {:op :proposal-escalated :proposal proposal :timestamp (ex/get-time-ms)}]
        (store/append-ledger! store fact)
        (assoc state :execution :escalated))
      state)))

(defn hold-node
  "Log hold (governor rejection) to audit ledger."
  [state store _ctx]
  (let [decision (:decision state)]
    (if (= decision :hold)
      (let [proposal (:proposal state)
            check-result (:check-result state)
            fact {:op :proposal-held :proposal proposal :violations (:violations check-result) :timestamp (ex/get-time-ms)}]
        (store/append-ledger! store fact)
        (assoc state :execution :held))
      state)))

;; ----------------------------- runner (simplified for demo) -----------------------------

(defn run-proposal
  "Execute a proposal through the workflow. Returns the final state."
  [request store advisor-impl phase-num]
  (let [state0 (intake-state request)]
    (-> state0
        (intake-node store nil)
        (advise-node store advisor-impl nil)
        (govern-node store nil)
        (decide-node store phase-num nil)
        (commit-node store nil)
        (escalate-node store nil)
        (hold-node store nil))))

;; ----------------------------- stub for time (WASM-portable) -----------------------------

#?(:clj (defn ex-get-time-ms [] (System/currentTimeMillis)))
#?(:cljs (defn ex-get-time-ms [] (js/Date.now)))

(def ^:private ex {:get-time-ms ex-get-time-ms})
