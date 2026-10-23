# Kafka final acceptance implementation plan

Goal: run three bounded correctness scenarios against the existing Broker and MySQL 8,
with fresh topics, groups and a restricted test schema, using current compiled source.

The user's A/B/C design and host execution requirement are the approved specification.
Work stays in the dirty working tree; no commits, deployments or application changes.

- [x] Add offline tests for namespace/cleanup guards and failure-gate behavior; observe red.
- [x] Add a small test-classpath Java entry using the production Consumer, error handler,
  transactional Writer and complete production Mapper XML. Persist actual deliveries,
  SQL snapshots, DLT records and committed offsets. Execute A, B, C in order.
- [x] Add one PowerShell host entry: bounded preflight, fresh source fingerprints,
  offline Maven compile/tests, bundle fresh classpath, run a short-lived Java test client,
  then cleanup only resources owned by this run, including a separate cleanup path.
- [x] Compile and run the offline tests and 33 existing targeted tests; validate the
  PowerShell entry without Docker. Preserve all original dirty-file hashes.
- [x] Review namespace safety, timeout cleanup and real-batch assertions. Report
  READY_TO_RUN until host evidence is returned; no freeze claim before real A/B/C PASS.

The Broker advertises kafka:19092 on its Docker network, with no host listener. The
host entry therefore uses the already-local front image only as a JRE for one ephemeral
test client (no application JAR startup, no new infrastructure). It does not execute a
test JVM inside the memory-constrained shared Broker. No image pull or resource changes.
Client limits: 384 MiB, 1 CPU, test heap 192 MiB; preflight must establish resource room.
Only its own client may be removed on timeout. Shared services are never stopped.

Offline completion: 43 Java checks (33 existing + 10 guard/fixture/cleanup tests),
11 PowerShell entry checks, and a separate JVM probe of the exact bundled classpath.
The complete Offline entry also passed when called outside the repository directory.
The reviewer-confirmed cleanup defects and Scala Jackson ledger round-trip defect
were fixed before handoff. Original 207 dirty file hashes remain unchanged.
Real Broker/MySQL A/B/C are NOT_RUN and require the host entry; freeze is pending.
