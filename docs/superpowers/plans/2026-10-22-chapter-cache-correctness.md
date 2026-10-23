# Chapter cache correctness closure

The user authorizes this bounded implementation in the existing dirty tree. No commits,
worktrees, deployments, Kafka changes or performance experiments.

## Design

A separate persistent generation key would require lifecycle/ABA management. Reuse the
existing unique, expiring loader token as the fill generation instead: acquire before
MySQL load; one Lua invocation checks the token and writes value+TTL atomically. All
three invalidators issue one DEL of content keys AND corresponding loader-token keys.
Invalidation revokes old fill rights; later owners use fresh UUIDs. Expiration or an
empty Redis restart also revokes rights. Existing token-safe unlock remains separate.
There is no new Redis GET/check/write window and no permanent metadata growth.

This is cache fencing, not a transaction spanning Redis and MySQL. Failed invalidation
or Redis restoring older persisted state can retain stale data until TTL. An in-flight
reader may return its previously loaded value; it cannot repopulate after its token was
successfully revoked. All three applications need the same invalidation protocol.

## Confirmed scope

- queryBookIndexAbout already uses findOwnedChapter; preserve and regress it.
- Existing Front author write/delete and Crawl DB writes already use afterCommit.
- Admin BookContent save/update/delete already resolve old/new identities; preserve.
- Add Admin BookIndex save/identity or storage changes/delete invalidation and Book
  batch-delete invalidation, with transaction callbacks. Do not add cascade deletes.
- Existing batch book deletion and directory deletion leave dependent rows: report
  this independent business behavior rather than change it for cache cleanup.

## Execution

- [x] Deterministically reproduce late stale fill, token expiry/replacement and null-fill races.
- [x] Add Admin index identity/delete and batch-book invalidation tests.
- [x] Implement token-fenced SET and paired-key invalidation without key format changes.
- [x] Implement only missing Admin invalidation hooks; preserve existing deletion SQL.
- [x] Run affected-module checks and existing ownership regressions; verify Kafka hashes.
- [x] Prepare safe optional real-Redis validation using a fresh namespace only; do not
  run the old fixed-key Redis IT or touch Docker. Clearly separate local from integration evidence.

## Review stop boundary

The existing Admin body editor reads its chapter's book identity before its update.
A concurrent chapter move can commit+invalidate A/B, B can be filled with the old body,
and the body update subsequently invalidates only A. Token fencing cannot fix wrong
invalidation identities. Closing that class of race requires a shared row-lock and
identity-validation protocol for body/ownership writers (including other write clients),
not only another transaction annotation. Per the user's scope-stop instruction, this
business expansion is NOT implemented. It prevents a full REDIS_READY_TO_FREEZE claim.
