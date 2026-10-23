# Stage B explicit reaction state implementation plan

User-approved scope: keep Stage A intact; LikeService/BookController/Lua, built-in/green/orange reaction controls and selected tests only. No migration, deployment, commits, pushes, frozen Redis/Kafka writes or new infrastructure.

Checkpoint: this chat work/stage-b/stage-a-files.zip + stage-a-checkpoint-sha256.json + stage-a-working.patch + git status; 4449 source/config files, before all Stage B edits.

- [x] RED: old toggle must reject pending/rejected targets and hidden-parent replies before any Redis access; new routes must reject anonymous requests.
- [x] Add LikeService nested ReactionState and explicit set/read methods. Reuse old Set keys; two-key Lua validates key types, applies SADD/SREM, returns counts and membership in one execution. Keep deprecated single-key toggle scalar count response and enforce the same visibility predicate. New read/write failures never fabricate successful zero state.
- [x] New POST setCommentLike/setCommentUnLike/setReplyLike/setReplyUnLike requires active=true/false. Authenticated GET commentReaction/replyReaction returns no-store snapshot. Old routes carry deprecation headers without changing existing JSON decimal-string count success data.
- [x] Shared frontend reaction-state.js: per-kind/id busy state, explicit POST from confirmed state, request tokens reject late callbacks, read back after failures without replaying writes. Update all active template copies and hydrate SSR/AJAX controls.
- [x] Selected Java mock/HTTP/wiring regressions plus Node double-click/late-response/error recovery tests. Extend existing real Redis IT on the private correctness Redis at 127.0.0.1:26381 using previously absent random keys and mocked DB visibility; no database writes.
- [x] Reuse fresh-report guard and existing Maven/Node/Redis test setup in one host PowerShell7 entry. Require real Redis tests not skipped and raw before/after Set evidence. Preserve Stage A hash comparison and report exactly the B changes.

Boundaries: approval can change between DB visibility check and Redis Lua; Redis atomic execution does not provide a DB/Redis transaction or global ordering across different intentions. Existing keys imply standalone Redis for two-key Lua, no cluster hash-tag migration. No claim of deployed acceptance.

Verified: reaction-20261019214052630; 41 Java tests, 0 failures/errors/skips, including 13 real Redis tests and 26 raw snapshots; 27 Node VM/static template checks. No deployed/browser acceptance.
