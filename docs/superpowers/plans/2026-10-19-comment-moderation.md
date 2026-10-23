# User interaction Stage A implementation plan

Goal: implement the user-approved moderation contract on interaction-moderation-20261019, preserving pre-existing changes. No commits, frozen experiment changes, production SQL, Docker access, or Stage B implementation.

Architecture: share one transactional moderation service and annotated mapper contract in novel-interaction; front/admin scan thin mapper adapters. Lock order book -> comment -> reply. moderation_version increments on every content/status change, counters change only by approved-status delta. Public queries filter status; private owner queries retain all statuses.

- [x] 1. RED: pending creation has zero public counts; hidden replies cannot be created; public/owner visibility differs. Run selected CommentCorrectnessTest offline, save failing output.
- [x] 2. Shared transaction service: create, audit with expected version, owner/admin edit to pending, delete, ordered batch deletion. Add real Mapper/transaction H2 tests and optional isolated MySQL mode for transition matrix, concurrency, stale versions, rollback, lock wait.
- [x] 3. Front/admin adapters, queries, endpoints and pages. Review content separately from moderation; owner can edit/re-submit same id; no ownership/counter mass assignment. HTTP permission/visibility tests.
- [x] 4. Migration package: read-only preflight, additive versions, guarded backup/recount/rollback; only scripts and fixture tests, no execution on existing DB.
- [x] 5. Run selected front/admin/common regression, review diff, prepare bounded host-only MySQL runner and evidence summary. Report real MySQL as pending until observed. Stop before Stage B.

Verification: Maven 3.9.9 offline, Java 21, selected tests only. Evidence in this chat work/stage-a and correctness performance/correctness/artifacts/moderation-*. Unit tests are not deployment acceptance.

Execution result: 2026-10-19 final host moderation-20261019211411360, Java66/66 no skips, UI13 + evidence3. No deployment or existing-schema migration. Stage B not started. User executed host command; no Docker access by Codex.
