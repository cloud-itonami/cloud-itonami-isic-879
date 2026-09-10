(ns rescare.operation
  "A synchronous decision pipeline orchestrating a single proposal
  workflow: intake -> advise -> govern -> decide -> {commit | hold |
  escalate}.

  NOTE: unlike most sibling `cloud-itonami-isic-*` actors, this is NOT
  yet wired into a real `langgraph-clj` StateGraph -- `run-proposal`
  is a plain function pipeline (`->` over intake-node/advise-node/
  govern-node/decide-node/commit-node/escalate-node/hold-node), not a
  compiled `langgraph.graph`. An `:escalate` decision is logged to the
  audit ledger but has NO resume/approve path -- there is no
  checkpointer, no `interrupt-before`, and no way for a human to later
  complete an escalated proposal. Production build should wire this
  into a real StateGraph with `interrupt-before #{:request-approval}`
  and a checkpointer, matching every other actor's HITL contract (see
  e.g. `mailorderops.operation`, cloud-itonami-isic-4791). One run = one
  proposal, no unbounded internal loops. The graph state is append-only
  in the store's audit ledger."
  (:require [rescare.store :as store]
            [rescare.advisor :as advisor]
            [rescare.governor :as governor]
            [rescare.phase :as phase]))

;; ----------------------------- time (WASM-portable) -----------------------------

#?(:clj (defn- now-ms [] (System/currentTimeMillis)))
#?(:cljs (defn- now-ms [] (js/Date.now)))

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
            record {:proposal proposal :decision :committed :timestamp (now-ms)}]
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
            fact {:op :proposal-escalated :proposal proposal :timestamp (now-ms)}]
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
            fact {:op :proposal-held :proposal proposal :violations (:violations check-result) :timestamp (now-ms)}]
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

