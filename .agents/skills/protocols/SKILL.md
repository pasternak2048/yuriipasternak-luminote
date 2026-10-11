---
name: sharaga-protocols
description: Manager-only orchestration rules for scope, isolation, evidence, verification, integration and delivery; roles receive only activated protocol outputs.
---
# Sharaga Protocols
Normative orchestration-time context. Yurko selects protocols and sends roles only the resulting evidence/constraints; do not load this skill into every role by default.

## Core
- Minimum context; fewest competent roles; risk changes verification depth, not team size.
- Context Firewall: pass evidence, not conclusions/raw reasoning.
- Load/inspect progressively; review diff first; preserve unrelated code; repository facts stay single-source.

## Evidence package
Base: `TASK:` `AC:` `FILES/DIFF:`. Add only when active: `RISK:` `BUDGET:` `INVARIANTS:` `A#:` `D#:` `BLAST_RADIUS:` `TEST_IMPACT:` `QA_FOCUS:` `EVIDENCE:`.

## Scope/design
- CHANGE_BUDGET bounds subsystem/files/refactor/API/dependencies/schema.
- BLAST_RADIUS lists plausible downstream behavior only.
- PARALLEL/COLLABORATE: lock contracts; name one `INTEGRATION_OWNER`; integration cannot silently alter contracts/boundaries.
- Critical assumptions are `A#`; disproving one invalidates dependents.
- Architecture decisions are `D#` with rationale/status; replacements declare `SUPERSEDES:` and require new evidence.

## Independent reasoning
- Unknown cause -> evidence-first INVESTIGATE.
- Multiple credible solutions -> isolated COMPETE plans, then `COMPETITION_DECISION` before implementation; compare correctness, invariants, change surface, regression risk, testability, complexity.
- Architecture disagreement -> independent DEBATE then synthesis.
- High regression risk -> isolated Review A/B from identical evidence; Yurko emits `REVIEW_SYNTHESIS` only after both finish.
- Critical safety/correctness -> RED_TEAM when justified.
- SHADOW_IMPLEMENTATION is exceptional, disposable and non-mergeable by default.

## Verification/delivery
- Derive TEST_IMPACT from changed/downstream surfaces.
- Validate fail-fast: cheapest decisive prerequisite first.
- Claims cannot exceed evidence; compile != behavior.
- QA validates AC independently from review.
- Findings: BLOCKER/MAJOR/MINOR/NIT; evidenced domain BLOCKER may STOP-THE-LINE.
- LOW confidence/conflicting evidence -> escalate, never guess.
- High-risk persisted/runtime changes define rollback consequences before promotion.
- Escaped regressions get `R#` + trigger/invariant/test target/source; mark `PERSISTENCE_REQUIRED` when durable coverage/docs are required.
- Git text derives from verified evidence.
- Release boundary: Yulya=behavior; Release Validation=exact artifact/policy; Khrys=promotion/action authorization.

## Selection
`unknown cause -> INVESTIGATE`; `multiple solutions -> COMPETE`; `architecture conflict -> DEBATE`; `independent work -> PARALLEL+CONTRACT_FIRST+INTEGRATION_OWNER`; `security -> SECURITY_REVIEW[/RED_TEAM]`; `performance -> PERFORMANCE_REVIEW`; `high regression -> BLAST_RADIUS+TEST_IMPACT+INDEPENDENT_REVIEW`; `critical assumption -> ASSUMPTION_REGISTER`; `high-risk persistence/runtime -> ROLLBACK_AWARE`; `low confidence -> ESCALATE`; `exceptional algorithm uncertainty -> SHADOW_IMPLEMENTATION`.
