---
name: android-device-validation
description: Device-sensitive Android validation guidance for behavior that depends on physical runtime state such as lock/unlock, screen on/off, AoD, notifications, services, permissions, process restart, overlays, or vendor/device behavior. Use on demand when acceptance cannot be established adequately by build/tests/static evidence alone.
---

# Android Device Validation

Augment an activated specialist validating Android behavior that depends materially on device runtime conditions.

This skill does not own QA, create a validation stage, advance gates, or replace QA/Manager/human-validation authority. Use the active specialist's existing output contract.

## Activate When

Use when acceptance materially depends on one or more of:

- physical device behavior;
- lock/unlock;
- screen on/off;
- AoD;
- notification arrival/dismissal;
- overlay visibility;
- AccessibilityService behavior;
- service/process restart;
- HOME/background/foreground transitions;
- permission changes;
- vendor/device-specific behavior;
- visual behavior that cannot be established from deterministic tests.

## Do Not Activate When

Do not load when:

- all relevant AC are adequately machine-verifiable;
- validation is purely unit/integration/static;
- task has no Android runtime/device-sensitive behavior;
- a human visual/device criterion is already isolated and no further device methodology is needed.

Do not expand QA scope merely because additional device scenarios are possible.

## Start From Acceptance Criteria

Validate the requested behavior, not the entire application.

For each device-sensitive criterion determine:

- required starting state;
- action/event;
- expected observable result;
- relevant cleanup/terminal state;
- whether validation can be automated, agent-observed, or requires a human.

Use `TEST_IMPACT` and `QA_FOCUS` when supplied.

Do not inherit developer conclusions as truth.

## Device State

Control or record relevant preconditions such as:

- device/model;
- Android/vendor version when material;
- screen state;
- lock state;
- AoD state;
- app foreground/background;
- service connection;
- permission state;
- process warm/cold/restarted;
- notification state.

Do not compare runs with materially different preconditions as though they were equivalent.

## Transition Validation

Where relevant, validate behavior across transitions rather than only steady state.

Examples include:

- unlocked -> locked;
- screen on -> screen off;
- screen off -> screen on;
- foreground -> HOME;
- process alive -> process restarted;
- permission granted -> revoked;
- idle -> notification arrival;
- active effect -> new notification.

Choose only transitions justified by AC, risk, regression evidence, or QA focus.

## Observation

Distinguish:

- expected observable behavior;
- actual observation;
- supporting machine/runtime evidence;
- subjective visual judgment.

For visual effects, useful observations may include:

- effect starts when expected;
- correct surface/edge is used;
- no obvious clipping or stale frame;
- completion/cleanup occurs;
- restart/replacement behaves as specified.

Do not convert subjective appearance into deterministic technical proof.

## Automated vs Human Validation

Machine-verifiable evidence may establish only the criteria it actually exercises.

Build/tests/static checks do not prove physical-device visual behavior.

When a required criterion cannot be performed or observed reliably by the active agent, use the existing human-validation path:

`STATUS: HUMAN_VALIDATION_REQUIRED`

This is a valid handoff, not a QA failure.

Identify the exact remaining criterion and provide a minimal reproducible validation sequence.

Never mark human-owned criteria `VERIFIED` before human evidence exists.

## Regression Scope

Validate nearby behavior only when justified by blast radius or known regression risk.

For lifecycle-sensitive changes, consider whether the changed path affects:

- initial start;
- repeated event;
- cancellation/replacement;
- cleanup;
- restart/reconnect.

Do not turn every device validation into a full regression suite.

## Evidence Discipline

Record enough evidence to distinguish:

- PASS;
- FAIL;
- NOT_TESTABLE or human-owned validation;
- unrelated observation.

A successful device run proves the tested scenario under its recorded conditions, not universal correctness across Android devices or lifecycle states.

## Output Discipline

Use the active specialist's existing output contract.

For QA, preserve the existing QA criterion/result semantics.

For engineers/reviewers, place device evidence or remaining uncertainty in their normal validation fields.

Do not emit an independent device-validation status or gate.