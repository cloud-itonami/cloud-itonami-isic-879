# cloud-itonami-isic-879

**Other Residential Care Activities** — ISIC Rev.4 class 879.

A coordination-only actor for residential care facilities not elsewhere classified (children's homes, shelters, halfway houses, non-medical residential rehabilitation facilities), behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-resident-note, schedule-family-or-guardian-visit, coordinate-supply-request, schedule-staff-shift-proposal, flag-safety-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Resident verified** — target must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — medication, clinical diagnosis, care-plan changes, physical restraint, guardianship/custody decisions, disciplinary actions, end-of-life decisions, and safety-authority overrides are permanently blocked.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: resident-note logging only (approval-gated)
  - Phase 2: + family/guardian visit, supply, shift proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (safety concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
kbb -M:dev -P

# Run tests
kbb -M:dev:test

# Run linter
kbb -M:lint

# Run demo
kbb -M:run
```

## Test suite

- `test/rescare/governor_test.kotoba` — unit tests of governor hard checks and scope exclusion
- `test/rescare/advisor_test.kotoba` — advisor proposal shape and consistency
- `test/rescare/phase_test.kotoba` — rollout phase logic
- `test/rescare/governor_contract_test.kotoba` — full graph integration, audit trail
- `test/rescare/store_contract_test.kotoba` — Store protocol and MemStore implementation

## Modules

- `rescare.store` — SSoT (MemStore, String-keyed resident directory, append-only ledger)
- `rescare.advisor` — contained intelligence node (mock + real-LLM seam)
- `rescare.governor` — independent compliance layer
- `rescare.phase` — staged rollout (0→3)
- `rescare.operation` — langgraph-clj StateGraph
- `rescare.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and ADR-2607152700 for design decisions.
