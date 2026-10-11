---
name: animation-engineering
description: Engineering guidance for time-dependent visual effects where correctness depends on animation timing, rendering progression, completion, interruption, restart, or renderer/playback lifecycle. Use on demand for non-trivial animation-sensitive work; do not load for ordinary static UI or simple transitions whose existing role guidance is sufficient.
---

# Animation Engineering

Augment an already activated specialist working on non-trivial animation behavior.

This skill does not own work, select roles, create workflow stages, advance gates, or replace role/protocol authority. Use the active specialist's existing output contract.

## Activate When

Use when task, risk, or evidence materially depends on one or more of:

- elapsed-time or frame-time progression;
- finite effect duration or completion;
- multi-phase animation behavior;
- interruption, cancellation, restart, replacement, or teardown;
- renderer ↔ playback/lifecycle interaction;
- Canvas/Path/Paint progression or animated geometry;
- interpolation/easing that affects behavioral correctness;
- visual transitions whose start/end continuity matters;
- per-frame state, work, or allocation concerns;
- watchdog/host lifetime coupled to animation completion.

Do not activate merely because a UI contains an animation.

## Do Not Activate When

Do not load for:

- static UI, styling, spacing, colors, typography, resources, or localization;
- trivial framework transitions with no meaningful timing/lifecycle contract;
- animation naming or list ordering;
- pure lifecycle work with no animation-sensitive behavior;
- pure performance work where animation semantics are irrelevant;
- visual validation that needs no implementation/timing reasoning.

## Core Model

For non-trivial effects identify, where relevant:

- **time source** — what drives progression;
- **start condition** — when progression begins;
- **progress** — how elapsed time maps to visual state;
- **phases** — distinct behavioral segments;
- **duration contract** — finite, infinite, or externally controlled;
- **completion contract** — what constitutes finished;
- **interruption semantics** — cancel, replace, restart, continue, or blend;
- **ownership** — which component owns playback and terminal cleanup.

Prefer elapsed-time-based progression over assumptions about frame count or refresh rate.

Do not infer runtime completion solely from what appears visually complete.

## Rendering Correctness

Keep stable geometry/state outside frame work when practical.

Avoid unnecessary per-frame allocation, object rebuilding, path reconstruction, or state churn.

For moving/wrapped geometry, reason about the full progression range, including boundaries and terminal frames. Check for:

- stale tails or segments;
- wrapped overlap;
- discontinuities at joins;
- one-frame flashes;
- geometry surviving completion;
- phase transitions that double-render or temporarily render nothing.

Treat visual continuity and lifecycle completion as separate concerns unless the design explicitly couples them.

## Timing and Transitions

Make phase boundaries explicit when behavior changes over time.

At each boundary determine:

- state immediately before;
- state at the boundary;
- state immediately after;
- whether both phases may render concurrently;
- whether previous phase state must be cleared.

Do not hide undefined transition semantics inside arbitrary timing constants.

Easing/interpolation must preserve required endpoints and monotonicity where the effect depends on them.

## Finite Effects

A finite effect needs an explicit terminal condition.

Verify that:

- progression reaches the intended terminal state;
- completion fires once;
- no active visual state survives unexpectedly;
- host/watchdog timing cannot terminate valid playback early;
- completion cannot leave the host alive indefinitely unless explicitly intended.

Renderer completion and host teardown are separate contracts unless explicitly designed otherwise.

## Cancellation, Restart and Replacement

Define what happens when a new event arrives during active playback.

Check:

- which state is retained or discarded;
- whether time restarts;
- whether stale callbacks can complete a newer effect;
- whether teardown from an old generation can remove a new one;
- whether replacement can briefly expose stale visual state.

Use generation/token/ownership mechanisms where overlapping asynchronous lifetimes require identity.

## Validation and Evidence

Match claims to evidence.

Deterministic tests may validate:

- progression math;
- duration/phase boundaries;
- terminal state;
- restart/cancellation rules;
- stale-generation rejection.

Build/tests alone do not validate:

- perceived smoothness;
- physical-device clipping;
- refresh-rate-specific appearance;
- actual AoD/lock-screen visual behavior.

Put unresolved device/visual criteria in the active role's existing `UNVERIFIED`, QA handoff, or human-validation path.

Do not emit an independent animation status.

## Cross-Ownership Work

Animation-sensitive behavior may span multiple owners, commonly rendering/UI and runtime/lifecycle.

Do not collapse ownership merely because both sides consume this skill.

When multiple engineers are routed, preserve Manager-defined ownership boundaries and Integration Owner semantics. Validate the combined candidate across the renderer/playback boundary.