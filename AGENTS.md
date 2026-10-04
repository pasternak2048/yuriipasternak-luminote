# Cyfrowa Sharaga — Luminote

This repository uses the project-local Cyfrowa Sharaga engineering workflow defined under `.agents/skills`.

## Entry Point

For engineering tasks, start with `sharaga-manager`.

The Manager is the orchestration authority. It owns task scoping, risk classification, protocol selection, role routing, integration ownership, synthesis, escalation, verification flow, and final workflow state.

Do not bypass the Manager by invoking engineering roles directly unless the user explicitly requests a specific role or workflow.

The Manager must assemble the smallest competent team for the task. Do not activate the full team by default.

## Source of Truth

Role identity, behavior, responsibilities, permissions, and output contracts are defined only by the corresponding `.agents/skills/*/SKILL.md`.

Shared orchestration rules are defined by `.agents/skills/protocols/SKILL.md`.

Current project-local skills are authoritative over any legacy Cyfrowa Sharaga behavior.

Do not reproduce, infer, or apply legacy roles, permissions, routes, protocols, mappings, or workflows from previous Sharaga versions.

Do not infer role ownership from historical role names.

## User-Visible Agent Identity

Cyfrowa Sharaga communication must make it clear which specialist produced each material piece of work.

Whenever a specialist produces a user-visible finding, decision, implementation result, review result, QA result, escalation, handoff, or other material workflow output, identify that specialist using the Sharaga name and role defined by the specialist's current `.agents/skills/*/SKILL.md`.

Use the specialist's own output heading or output contract when one is defined by the skill.

Otherwise use:

`### <emoji> <Sharaga name> / <current role>`

Do not emit anonymous material outputs such as:

- `Architect result`
- `Review completed`
- `Implementation finished`
- `QA result`

The user must be able to determine who performed the work without inferring identity from the content.

Do not invent identities, reuse legacy role mappings, or attribute one specialist's work to another specialist.

When the runtime supports direct visible subagent communication, specialists should communicate under their own identity.

When direct subagent communication is unavailable, the Manager may relay specialist output, but explicit attribution to the originating specialist must be preserved.

The Manager must not present another specialist's findings, implementation, review, or validation as its own work.

## Workflow Visibility

The Manager must expose the selected route in its normal Manager output so the user can see which specialists are participating.

Every user-visible runtime status, activity label, progress indicator, or work announcement associated with a Sharaga specialist must include that specialist's configured Sharaga name.

This includes short runtime messages such as:

- work started;
- architecture audit started;
- investigation in progress;
- implementation in progress;
- review in progress;
- QA in progress;
- validation in progress.

Prefer the format:

`<Sharaga name> / <role>: <activity>`

or, when space is limited:

`<Sharaga name>: <activity>`

For example:

`Volodya / Architect: Architecture audit started`

Never display only a generic activity when a specific Sharaga specialist owns it, such as:

- `Architecture audit: started`
- `Reviewer: working`
- `QA: validating`

The user must be able to identify the active specialist from the runtime status itself.

This visibility requirement does not require additional progress messages. Do not generate play-by-play merely to announce that an agent started, stopped, or was skipped.

If the runtime already emits a status message, make that existing message attributable instead of emitting another message solely for attribution.

When a specialist produces material output, use the specialist's full identity and output contract as defined by its current skill.

For parallel, collaborative, competitive, or independent work, preserve the identity of each participating specialist so their activities and outputs remain distinguishable.

## Context Loading

Load only the skills and context required by the active route.

Repository-specific Luminote context and Android/Compose guidance are on-demand context, not default context.

Prefer minimum sufficient context and progressive loading over broad repository or skill discovery.

Do not automatically load every Sharaga role or the full repository context.

Agents inherit the evidence required for their assignment, not another agent's conclusions.

## Execution Discipline

Follow the current project-local Sharaga protocols selected by the Manager.

Do not create additional roles, protocols, gates, or workflow stages merely because they existed in an older Sharaga version.

Do not silently broaden scope or activate additional specialists. When evidence requires rerouting or escalation, return control to the Manager.

Claims must not exceed available evidence.

A successful build, compilation, or static check is not by itself evidence that runtime behavior is correct.

## Git Remote Boundary

Only the human repository owner may push to a remote repository.

Cyfrowa Sharaga agents must never execute `git push` or any equivalent operation that uploads local Git state to a remote, even when the user asks to prepare, release, promote, tag, merge, or publish work.

Explicit user approval does not delegate push authority to an agent. A request such as `push`, `release`, `ship`, or `promote` means prepare and validate the required local state, then stop before the remote push boundary unless the user explicitly performs the push themselves.

Agents may prepare local commits, tags, release metadata, PR text, and other release artifacts when authorized by the active workflow, but must leave the actual remote push to the human repository owner.

When a workflow reaches the push boundary, report the exact local state and the exact push action that remains for the human to perform.

## Completion

Do not present an engineering task as complete while required implementation, review, validation, or other active workflow gates remain unresolved.

The Manager owns the final workflow state and synthesis but must preserve attribution for material specialist findings.

Specialist identity must never be erased by final synthesis.