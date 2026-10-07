---
name: state-machine-analysis
description: Formal state/transition analysis for behavior with multiple states, lifecycle transitions, ordering constraints, cancellation, restart, or competing events. Use on demand when explicit state modeling materially reduces ambiguity; do not load for ordinary state handling already clear from code and role guidance.
---

# State Machine Analysis

Augment an activated specialist when behavior is difficult to reason about reliably without explicit states and transitions.

This skill does not own architecture, critique, implementation, or validation. It does not select roles, create workflow stages, advance gates, or replace role/protocol authority.

## Activate When

Use when task, risk, or evidence includes meaningful complexity from:

- multiple behavioral states;
- lifecycle/state-machine changes;
- competing events;
- cancellation/restart;
- ordering constraints;
- asynchronous transitions;
- stale generations/owners;
- ambiguous terminal states;
- repeated bugs caused by implicit state;
- transitions spanning components.

Prefer activation when writing down the state model can expose defects that ordinary control-flow inspection may miss.

## Do Not Activate When

Do not load for:

- simple boolean/local UI state;
- straightforward linear control flow;
- ordinary Compose state with obvious ownership;
- architecture work where states/transitions are not relevant;
- lifecycle code whose behavior is already explicit and unambiguous.

Do not formalize a state machine merely because one can be invented.

## Model the Minimum Machine

Define only states relevant to the behavior under analysis.

For each relevant state identify:

- meaning;
- owner;
- allowed incoming events;
- allowed outgoing transitions;
- terminal/non-terminal nature.

Then identify relevant events and transitions using a compact form such as:

`STATE_A + EVENT_X -> STATE_B`

Include guards or side effects only when they affect correctness.

Avoid mirroring every implementation detail into the model.

## Invariants

State invariants describe what must remain true regardless of path.

Examples:

- only one active owner may control a resource;
- terminal state cannot return to active without a new start event;
- stale generation cannot mutate current state;
- cleanup is idempotent;
- completion occurs at most once.

Where useful, connect important invariants to the existing canonical `A#`/`D#` evidence rather than creating a parallel identifier system.

## Transition Audit

For important transitions ask:

- Is the source state valid?
- Is the event valid in that state?
- Is the target state uniquely determined?
- What happens if the event repeats?
- What happens if another event arrives first?
- Is the transition atomic where it needs to be?
- Can stale asynchronous work perform it later?
- Does cleanup occur before or after ownership changes?

Look especially for:

- impossible states;
- ambiguous transitions;
- missing transitions;
- duplicate completion;
- re-entry after terminal state;
- stale callbacks;
- lost cancellation;
- ordering-dependent outcomes.

## Cancellation and Restart

Cancellation is behavior, not absence of behavior.

Define:

- which state cancellation is valid from;
- resulting state;
- cleanup responsibility;
- whether completion callbacks still run;
- whether restart creates new identity/generation;
- what old work is allowed to do afterward.

For restart/replacement, explicitly reason about old and new owners coexisting temporarily.

## Concurrency

Do not assume code order equals runtime order across asynchronous boundaries.

Identify transitions that can race and the synchronization/identity mechanism that makes the outcome deterministic.

If correctness depends on undocumented timing rather than an explicit ordering/ownership rule, surface it as a risk.

## Evidence

Use the model to produce concrete findings, not ceremony.

Useful evidence includes:

- a missing or invalid transition;
- violated invariant;
- demonstrated race/order path;
- unreachable or ambiguous state;
- test covering a transition/invariant;
- runtime evidence confirming event order.

Do not claim runtime correctness solely because the state model is internally consistent.

## Output Discipline

Use the active specialist's existing output contract.

The state model may appear as compact supporting evidence when it materially helps the assignment, but do not create a separate state-machine workflow artifact or status.

Architectural decisions remain Architect/Manager-owned; independent challenge remains Critic-owned; implementation remains Engineer-owned.