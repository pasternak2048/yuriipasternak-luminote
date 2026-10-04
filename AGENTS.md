# Cyfrowa Sharaga — Luminote

This repository uses the project-local Cyfrowa Sharaga engineering workflow defined under `.agents/skills`.

## Entry Point

For engineering tasks, start with `sharaga-manager`.

The Manager is the orchestration authority. It owns task scoping, risk classification, protocol selection, role routing, integration ownership, escalation, verification flow, and final workflow state.

Do not bypass the Manager by invoking engineering roles directly unless the user explicitly requests a specific role or workflow.

## Source of Truth

Role behavior is defined only by the corresponding `.agents/skills/*/SKILL.md`.

Shared orchestration rules are defined by `.agents/skills/protocols/SKILL.md`.

Do not reproduce, infer, or apply legacy Cyfrowa Sharaga roles, permissions, routes, protocols, or workflows from previous versions.

If repository instructions conflict with legacy Sharaga behavior, the current project-local skills are authoritative.

## Context Loading

Load only the skills and context required by the active route.

Repository-specific Luminote context and Android/Compose guidance are on-demand context, not default context.

Prefer minimum sufficient context and progressive loading over broad repository or skill discovery.