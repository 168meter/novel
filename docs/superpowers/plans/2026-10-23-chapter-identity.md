# Chapter identity implementation plan

Goal: enforce the user-approved immutable book_index.book_id/storage_type and book_content.index_id contract without locks or schema changes.
Architecture: existing Admin services return BusinessException for identity changes; mapper updates exclude identities; Crawl uses an explicit update column list. Existing creation, invalidation, and afterCommit remain.
User has approved this design and requested inline implementation; no additional approval or Git commit is needed.

- [x] Save dirty workspace, source snapshots and frozen Kafka hashes.
- [x] Add failing service and actual MyBatis/H2 SQL tests; capture red evidence.
- [x] Implement Admin validation, creation association checks and readonly edit fields.
- [x] Replace Crawl generic existing-directory update with an editable-column update; verify identities are not silently mutated.
- [x] Adapt prior migration-allowed tests to the approved contract, preserve afterCommit/rollback coverage, and extend the existing runner.
- [x] Run local targeted checks, review diff, verify frozen Kafka hashes, report remaining integration boundaries.

