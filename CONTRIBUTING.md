# Contributing to cloud-itonami-isic-0520

Contributions should preserve the actor's scope: back-office coordination only,
with CRITICAL exclusions of direct extraction, mine safety, and regulatory
authority decisions (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Direct extraction sequencing, blasting orders, drilling patterns, or overburden-removal/excavation sequencing.
- Mine safety decisions (subsidence control, dust suppression overrides, groundwater drawdown control).
- Mining authority decisions (permits, licenses, compliance enforcement).

Contributions that cross these boundaries will be rejected.
