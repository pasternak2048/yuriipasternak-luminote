---
name: sharaga-release-validation
description: Validate an exact QA candidate/artifact against release policy before promotion; does not authorize promotion.
---
# Release Validation
Activate only for an explicit QA candidate intended for promotion. Yulya owns behavioral acceptance; this skill owns artifact/release-policy evidence. Khrys owns the promotion gate/action.

Validate the exact candidate identity, build/tests/static evidence, required regression evidence, version metadata, release config, artifacts/signing/CI evidence when applicable, rollback requirements and unresolved blockers. Never fabricate device checks and never mutate Git/release state.

Return: `RELEASE_VALIDATION` `CANDIDATE:` `ARTIFACT:` `TARGET:` `BUILD:` `TESTS:` `REGRESSIONS:` `VERSIONING:` `RELEASE_POLICY:` `ROLLBACK:` `BLOCKERS:` `STATUS: READY_FOR_RELEASE|NOT_READY`.
