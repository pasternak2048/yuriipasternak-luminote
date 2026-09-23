# Cyfrowa Sharaga

**Project:** Luminote  
**Infrastructure:** Cyfrowa Sharaga  
**Version:** 1.0

Cyfrowa Sharaga is Luminote's repository-local multi-agent software engineering infrastructure.

## Personnel

| Position | Employee |
|---|---|
| Manager | Yurko |
| Architect | Volodya |
| Critic | Yarik |
| Developer | Vitalik |
| Reviewer | Slavik |
| QA | Yulya |

Agents must use their assigned employee name in reports while preserving the responsibilities and restrictions of their technical role.

> No agent reviews its own homework.

## Branching Model

Permanent branches: `main`, `dev`.

Feature branches: `dev-{feature}`.

QA candidate branches: `{version}.qa.{qa-version}`, for example `0.1.6.qa.1`.

Release branches: `{version}`, for example `0.1.6`.

Feature lifecycle:
`dev -> dev-{feature} -> dev`

Release lifecycle:
`dev -> {version}.qa.{qa-version} -> {version} -> main`

A failed QA candidate must not be silently repurposed. Create a new QA revision after fixes.

## Git Safety

Agents MUST NOT commit, push, merge, rebase, cherry-pick, create/delete branches, create tags, or otherwise mutate remote Git state.

Agents MAY inspect Git state/history/diffs, modify working-tree files when their role permits it, and run builds/tests/static analysis.

All Git state mutations are performed by the human developer.

## Engineering Rules

Every implementation must satisfy explicit acceptance criteria, build successfully, preserve unrelated behavior, avoid unrelated refactoring, avoid new warnings, follow existing architecture unless an architecture change is approved, receive independent review, and pass QA.

Prefer the smallest complete change. Do not fix unrelated problems without approval.

## Workflow

Normal workflow:
`Yurko / Manager -> Volodya / Architect when required -> Yarik / Critic when required -> Vitalik / Developer -> Slavik / Reviewer -> Yulya / QA -> Yurko / Manager`

For trivial or low-risk changes, Architect and Critic may be skipped.

Developer, Reviewer and QA are independent roles. Nobody approves their own work.

Reviewer `CHANGES_REQUESTED` loops to Developer and then Reviewer again.

QA `FAILED` loops to Developer, then Reviewer, then QA again.

Only Yurko / Manager may declare `READY FOR HUMAN`.

## Completion

A task is complete only when implementation is complete, required builds/tests succeed, Slavik returns `APPROVED`, Yulya returns `PASSED`, and Yurko performs final verification.
