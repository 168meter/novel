# A+B joint deployment preparation plan

User-authorized: implement and offline-check one host PowerShell 7 entry; host executes Docker. Keep uncommitted work, existing schemas/containers/volumes and frozen experiments. No commits or cleanup.

- [x] Preserve source/config and dirty Git inputs; restore only edits made during the first joint-preparation attempt to the two old deployment tools, keeping the attempt separately.
- [x] Add Run-InteractionAcceptance.ps1 using Native-Utf8.ps1, existing Dockerfiles, fresh-report helper, new project/ports and no persistent volumes. Fail before writes on identity/resource/port conflicts.
- [x] Create new trial, restore, and app schemas from read-only novel_ab snapshot. Lock new app principal, assert no sessions and denied DML, keep app containers absent; validate preflight, statuses, backup restore, DDL/recount/instant rollback in trial, then app recount. Never run rollback after apps start.
- [x] Add actual Shiro/password-login HTTP test with SQL and Redis assertions for comment/reply lifecycle, permission denial, versions, counts, explicit reactions and legacy compatibility. Use distinct test IDs and retained evidence.
- [x] Prepare Playwright against installed Edge/bundled runtime for actual template/resource hashes, selected states/refresh/AJAX/double click/failure recovery/late responses; accurately mark absent/partial/failed.
- [x] Parse and offline-test orchestration guards; compile tests and run related local suites. Host run is required for migration/deployment/browser results.
- [x] Provide one command and a report separating all five status categories. Preserve environment after host execution.

Host evidence: joint-20261020152352233 + browser-continuation-20261020154408664. Verified 511 Java + 1 deployed HTTP + 27 real Edge browser checks; trial/restore/app migration validation passed. Original failed reports retained. No production rollout or cleanup. Index and other boundaries documented in interaction-joint-result-20261020.md.
