# Moderation migration (prepared, not executed)

Run 01 read-only preflight against an explicitly selected schema and review every issue and count difference. Do not automatically approve legacy status 0, delete orphan rows, or change uniqueness. Resolve invalid data via a separately approved plan.

Before 02/03: take a full schema backup; stop ALL comment/reply/related book/user writes from old and new front/admin; set both session variables named at the top of 02. The flag is an operator acknowledgement, not a mechanism that stops writers. Inspect SHOW CREATE TABLE/INDEX; require MySQL 8/InnoDB and existing UNIQUE(book_id,create_user_id). 02 DDL has implicit commits and may be partial on error. No DROP TABLE or data cleanup is included. No new pagination index is required for correctness; retain existing indexes.

03 snapshots original counts (including NULL), fingerprints all comment/reply rows, then recalculates only approved counts in one transaction. Parent visibility does not alter internal approved-reply counts. Fixed backup and procedure names intentionally fail instead of overwriting evidence on a repeated/partial run. Inspect retained backup/procedure state on failure; do not blindly rerun.

Keep writes paused while verifying and deciding whether to run 04. 04 refuses changed rows/status/version/counts and restores old counts atomically; it retains new version columns and all backup evidence. Application rollback and count rollback must be coordinated: old code counts all statuses, new code counts approved only. Do not start either version during a count rollback. Rollback after accepting new traffic needs a separately reviewed reconciliation, not this immediate rollback script.

Deploy order: maintenance pause -> preflight/backup -> version DDL -> count snapshot/recount -> new front/admin together -> checks -> resume writes. Historic 0/2 content becomes private without changing stored audit state. After new traffic begins, prefer forward repair. None of these scripts has been executed against existing data by Codex.
