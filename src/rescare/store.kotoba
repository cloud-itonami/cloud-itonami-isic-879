(ns rescare.store
  "SSoT for the ISIC-879 residential-care COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a residential care
  facility for residents not elsewhere classified (children's homes, shelters,
  halfway houses, non-medical rehabilitation facilities, etc.). It covers:
  daily care-note logging, family/guardian visit scheduling, consumable supply
  coordination, staff shift proposals, and safety-concern flagging (falls,
  wellbeing incidents, child protection concerns).

  It never touches medication administration, clinical diagnosis/assessment,
  care-plan modifications, physical restraint decisions, guardianship/custody
  decisions, disciplinary actions, end-of-life/DNR decisions, or any
  safety-authority override -- see `rescare.governor`'s `scope-exclusion-violations`,
  a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `residents` directory keyed by `:resident-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified resident record must exist before ANY proposal
  for that resident may ever commit or escalate -- `rescare.governor`'s
  `resident-unverified-violations` re-derives this from the resident's own
  `:registered?`/`:verified?` fields, never from proposal self-report,
  the SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which resident a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (resident [s resident-id] "Registered resident record, or nil.
    Resident map: {:resident-id .. :name .. :registered? bool :verified? bool}.")
  (all-residents [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-residents [s residents] "replace/seed the resident directory (map resident-id->resident)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained resident directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:residents
   {"resident-1" {:resident-id "resident-1" :name "Emma Brown (age 14, in shelter)"
                   :registered? true :verified? true}
    "resident-2" {:resident-id "resident-2" :name "Diego Lopez (age 17, rehabilitation)"
                   :registered? true :verified? true}
    "resident-3" {:resident-id "resident-3" :name "Sophie Chen (age 8, in intake)"
                   :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (resident [_ resident-id] (get-in @a [:residents resident-id]))
  (all-residents [_] (sort-by :resident-id (vals (:residents @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-residents [s residents] (when (seq residents) (swap! a assoc :residents residents)) s))

(defn seed-db
  "A MemStore seeded with the demo resident directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `residents` map (resident-id string ->
  resident map) -- the primary test/dev entry point. `residents` may be empty
  (an unregistered-everywhere store)."
  [residents]
  (->MemStore (atom {:residents (or residents {}) :ledger [] :coordination-log []})))
