---
name: android-runtime-debugging
description: Evidence-first Android runtime debugging guidance for failures involving services, processes, overlays, AccessibilityService, notifications, permissions, background execution, screen/AoD state, or lifecycle behavior. Use on demand when runtime cause is unknown or static code evidence is insufficient.
---

# Android Runtime Debugging

Augment an activated specialist investigating or fixing Android runtime behavior.

This skill does not own the investigation or implementation, select roles, create workflow stages, advance gates, or replace role/protocol authority. Use the active specialist's existing output contract.

## Activate When

Use when task or evidence involves runtime uncertainty such as:

- behavior differs from code/test expectations;
- service start/stop/recreation;
- AccessibilityService connection or dispatch;
- overlay creation/removal/visibility;
- notification delivery or listener behavior;
- process death/restart;
- screen on/off, lock screen, or AoD;
- permission/app-op state;
- background execution restrictions;
- Doze or power-management interaction;
- lifecycle ordering visible only at runtime;
- device/vendor-specific behavior requiring evidence.

## Do Not Activate When

Do not load for:

- known deterministic code defects;
- ordinary implementation with sufficient static evidence;
- pure UI/rendering work with no runtime uncertainty;
- Gradle/build-only failures;
- generic architecture discussion without a runtime question.

## Evidence First

Do not begin with a preferred explanation.

Establish:

1. expected behavior;
2. observed behavior;
3. reproduction conditions;
4. relevant process/component state;
5. event ordering;
6. smallest boundary containing the divergence.

Separate:

- observation;
- hypothesis;
- confirmed cause;
- unresolved uncertainty.

Logs are evidence of emitted events, not proof of events that were never instrumented.

Absence of a log line is not automatically evidence that code did not execute.

## Runtime Evidence

Use the smallest useful evidence source.

Typical sources include:

- targeted `adb logcat`;
- `dumpsys activity`;
- `dumpsys activity services`;
- `dumpsys package`;
- `dumpsys notification`;
- `dumpsys deviceidle`;
- `dumpsys power`;
- app/service process state;
- permission and app-op state;
- focused application diagnostics.

Avoid collecting large undirected dumps when a narrower source can answer the question.

Capture timestamps/order when sequencing matters.

## Reproduction

Make runtime conditions explicit when relevant:

- screen state;
- lock state;
- AoD state;
- foreground/background;
- process already alive vs cold start;
- service connected/disconnected;
- permission state;
- notification source/state;
- previous active effect;
- device/vendor.

Prefer a minimal repeatable sequence over broad exploratory interaction.

If reproduction is inconsistent, record that as evidence rather than converting intermittent behavior into a deterministic claim.

## Lifecycle and Identity

For asynchronous Android components, determine:

- component/process owner;
- creation/destruction boundaries;
- reconnect/restart behavior;
- stale callback possibility;
- whether state survives component recreation;
- whether old work can mutate a newer runtime generation.

When identity matters, trace token/generation/instance information rather than relying only on timestamps.

## Narrowing the Boundary

Progressively distinguish whether the defect is primarily in:

- event arrival;
- routing/dispatch;
- state transition;
- service/host lifecycle;
- rendering/playback;
- permission/platform restriction;
- process lifetime;
- device/vendor behavior.

Do not change multiple boundaries merely to see whether the symptom disappears.

Prefer instrumentation before speculative production changes when evidence cannot distinguish competing causes.

## Fix Validation

After a candidate fix:

- reproduce the original failing sequence;
- confirm the expected runtime transition;
- look for stale/duplicate behavior;
- exercise relevant restart/reconnect/cleanup paths;
- distinguish machine-verified behavior from device-only or human-observed behavior.

A successful build or unit test does not close a runtime-only defect.

## Output Discipline

Use the active role's normal contract.

For investigation, preserve evidence-backed separation between:

- observed facts;
- likely/confirmed root cause;
- remaining uncertainty.

For implementation, place runtime evidence under the role's normal `VALIDATED`/`UNVERIFIED` fields.

Do not emit an independent runtime-debugging status.