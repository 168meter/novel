# 首页7天/15天推荐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline, or superpowers:subagent-driven-development when implementer permissions are available. Execute task-by-task with TDD and review checkpoints; commits remain user-owned.

**Goal:** 仅将本周强推5本及热门推荐6本改成阅读聚合驱动，保留其他人工栏目。

**Architecture:** 一条有界候选SQL计算两个窗口，纯组装器生成两个推荐组。专用单线程任务每5分钟生成完整首页快照；首页依次使用Redis、本机快照和只读后台配置兜底，绝不在请求内聚合日表。

**Tech Stack:** Java21、Spring Boot3.4、MyBatis、MySQL8、Redis、Jackson、Micrometer、JUnit5/H2。

**Spec:** `docs/superpowers/specs/2026-09-14-home-popular-recommendation-design.md`

## Global Constraints

- Worktree: `D:\offer\novel-plus\.worktrees\chapter-performance`, branch `feature/chapter-performance`.
- type0/1/4保持人工配置；newsList、点击/新书/更新榜不变。type2最多5、type3最多6。
- Shanghai窗口均包含今天：7天today-6、15天today-14，上界today+1排除未来。
- 阅读秒数主排序，累计visit_count同分排序，bookId升序稳定排序；不做加权评分/每日点击采集。
- type2/3要求存在非VIP书籍、word_count>0及非VIP章节对应非空DB正文；不误用book_status作为上下架。
- 每5分钟刷新，Redis TTL15分钟，本机成功快照最大24小时；完整构建后一次SET，不先DEL。
- 不更新book_setting，不调用原initIndexBookSetting；人工配置不足允许少展示。
- 请求不聚合日表；冷启动兜底单飞，有界SELECT，短期本机缓存；数据库失败空组可用，不无限重试。
- 不改用户三份已有配置，不自动git add/commit，不合并。日志/标签不包含身份、正文或bookId。

## Files and interfaces

All new main Java files below live in `novel-front/src/main/java/com/java2nb/novel/recommendation/`; tests use matching `src/test/java` paths. Mapper XML lives in `novel-front/src/main/resources/mybatis/mapping/HomeRecommendationMapper.xml`.

```java
record RecommendationWindow(LocalDate weekStart, LocalDate hotStart, LocalDate endExclusive) {
    static RecommendationWindow from(Clock clock); // one clock.instant(), Shanghai
}
class PopularBookCandidate extends BookSettingVO {
    private BigInteger weekSeconds;
    private BigInteger hotSeconds;
    private Long visitCount; // getters/setters for MyBatis
}
@Mapper interface HomeRecommendationMapper {
    List<PopularBookCandidate> listCandidates(
        @Param("weekStart") LocalDate weekStart,
        @Param("hotStart") LocalDate hotStart,
        @Param("endExclusive") LocalDate endExclusive);
    List<BookSettingVO> listConfigured(); // bounded slots, eligible only for type2/3
}
class HomeRecommendationAssembler {
    Map<String,List<BookSettingVO>> compose(List<PopularBookCandidate> candidates,
        List<BookSettingVO> configured);
    Map<String,List<BookSettingVO>> configuredOnly(List<BookSettingVO> configured);
}
class HomeRecommendationService {
    Map<String,List<BookSettingVO>> getHome();
    void refresh();
}
class HomeRecommendationRefreshJob { void refresh(); }
```

Snapshot representation: version1, generatedAt Instant, five groups of complete BookSettingVOs. Store encoded JSON plus generation time in a single immutable local value; deserialize/copy for consumers to prevent mutable DTOs corrupting shared state. Dedicated Redis key `novel:home:reading-recommendation:v1`.

## Verification commands

Maven command for this machine (use discovered runtime when executing elsewhere):

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  "-Dmaven.repo.local=$([Environment]::GetFolderPath('UserProfile'))\.m2\repository" `
  '-Dmaven.compiler.fork=true' '-Dmaven.test.skip=false' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=RecommendationWindowTest' `
  -pl novel-front -am test
```

Substitute each task's exact test list for Dtest. Observe missing implementation RED, then implementation GREEN. No live database writes in ordinary tests.

### Task 1: Window, candidate query and configured eligibility

**Files:** Create RecommendationWindow.java, PopularBookCandidate.java, HomeRecommendationMapper.java, mapper XML; RecommendationWindowTest.java, HomeRecommendationMapperTest.java.

**Produces:** Window and mapper interfaces above; max16 candidate rows, capped configured type0..4 slots.

- [ ] Write window test with fixed `2026-09-14T16:00:00Z` (Shanghai Sep15):

```java
var window = RecommendationWindow.from(Clock.fixed(
    Instant.parse("2026-09-14T16:00:00Z"), ZoneOffset.UTC));
assertThat(window.weekStart()).isEqualTo(LocalDate.of(2026,9,9));
assertThat(window.hotStart()).isEqualTo(LocalDate.of(2026,9,1));
assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2026,9,16));
```

- [ ] Add real H2/MyBatis fixture tables book, book_index, book_content, book_setting and book_reading_daily. Insert day-6/day-7/day-14/day-15/today/tomorrow rows, equal reading/different clicks, multi-chapter book, missing book, VIP book/chapter, empty/missing content. Assert exact sums and IDs, no multiplied sums, max16 output rows. Configured fixtures assert type0/1/4 unchanged order and caps; type2/3 eligible only.
- [ ] Run `RecommendationWindowTest,HomeRecommendationMapperTest`, confirm RED.
- [ ] Implement SQL using aggregate CTE, eligibility EXISTS, and window ranking. Core independent boundary:

```sql
SUM(CASE WHEN stat_date >= #{weekStart} THEN credited_seconds ELSE 0 END) AS week_seconds,
SUM(credited_seconds) AS hot_seconds
-- WHERE stat_date >= #{hotStart} AND stat_date < #{endExclusive}, GROUP BY book_id
-- rank eligible books by week_seconds DESC, visit_count DESC, book_id ASC
-- and hot_seconds DESC, visit_count DESC, book_id ASC separately
-- retain (week_seconds > 0 AND week_rank <= 5) OR (hot_seconds > 0 AND hot_rank <= 11)
```

Use explicit VO metadata columns, not SELECT*. Configured SQL ranks each type by sort and stable setting ID before applying per-type caps; apply recommendation eligibility to type2/3 before ranking. All params bound; no request-controlled SQL fragments.
- [ ] Rerun tests GREEN, review and `git diff --check`; user commit `feat: query bounded home reading candidates` with only Task1 files.

### Task 2: Independent groups, fallback and immutable assembly

**Files:** Create HomeRecommendationAssembler.java and HomeRecommendationAssemblerTest.java.

**Consumes:** Candidates/configured lists. **Produces:** compose/configuredOnly interfaces above, always keys0..4.

- [ ] Build literal fixtures: book11 week90/hot90, book12 week30/hot300, book13 week0/hot240; assert week prefers11 while hot prefers12 then13 when not present in final week group. Add enough fixtures to fill week5/hot6; test same-window seconds ties resolve clicks then IDs.
- [ ] Assert original type0/1/4 fields/order preserved, own-group configured fallback, group duplicates removed, hot excludes week when enough independent books, shortage allows cross-group duplicate, shortage never creates within-group duplicate. Mutate returned VO and assert original candidate/configured inputs unchanged.
- [ ] Run `HomeRecommendationAssemblerTest` RED.
- [ ] Implement BigInteger comparisons, then group-specific fallback. Select unique hot candidates first, fill distinct type3 configured candidates next, then allow skipped overlapping candidates/configured only if still short. Copy DTOs before setting type2/3 sort; do not alter original instances. configuredOnly uses same caps/group copying but no algorithm.
- [ ] Run Task1+Task2 tests GREEN, review; user commit `feat: assemble weekly and fifteen-day recommendations`.

### Task 3: Snapshots, bounded scheduling and cold-start single flight

**Files:** Create HomeRecommendationService.java, HomeRecommendationRefreshJob.java, HomeRecommendationConfig.java and matching ServiceTest/RefreshJobTest/ConfigTest.

**Consumes:** Clock, mapper, assembler, StringRedisTemplate, ObjectMapper, MeterRegistry. **Produces:** getHome/refresh interfaces; dedicated closeable single-thread scheduler.

- [ ] Use fixed/mutable Clock and actual ObjectMapper; fake only DB/Redis I/O. Assert redis-first/local-second/configured-last, corrupt/version/future/expired/duplicate/over-cap snapshots rejected; shared output mutation cannot alter later reads. DB refresh failure preserves old result; Redis write failure publishes successful local result; genuinely empty successful generation publishes empty groups.
- [ ] Latch-based concurrent cold-start test: one mapper.listConfigured invocation, no mapper.listCandidates from request, bounded waiters/no task submissions, failed fallback cached briefly to prevent request storms. Subsequent fallback after30s can retry. Local successful snapshot older24h cannot be used.
- [ ] Run `HomeRecommendationServiceTest,HomeRecommendationRefreshJobTest,HomeRecommendationConfigTest` RED.
- [ ] Implement getHome using validated Redis JSON, valid AtomicReference local JSON, then synchronized double-check configured cache30s. DB fallback exception -> empty five groups cached30s. Redis exception does not propagate. refresh reads Clock once, queries configured plus bounded candidates, composes, validates and publishes immutable JSON; DB/encoding failure retains previous, no DEL. Use Redis TTL15min and SET once.
- [ ] Configure a dedicated ThreadPoolTaskScheduler(poolSize1), schedule startup refresh then fixed-delay5min, cancel/close on shutdown; do not use front executor or default cleanup scheduler. No executor submit in HTTP path. Record refresh result(db_error/redis_error/success), request source(redis/local/configured/empty), generation duration and local snapshot age without high-cardinality tags. Read snapshots refresh local age gauge; keep configured fallback separate from successful algorithm snapshot.
- [ ] Rerun all recommendation tests GREEN, review; user commit `feat: cache home recommendation snapshots safely`.

### Task 4: Integrate only selected homepage groups

**Files:** Modify BookServiceImpl.java; create HomeRecommendationIntegrationTest.java, HomeRecommendationTemplateTest.java. Preserve PageController and BookController signatures, templates unless a verified integration defect requires minimal change.

**Consumes:** HomeRecommendationService.getHome. **Produces:** Existing listBookSettingVO delegates to service without initializing/replacing book_setting.

- [ ] Test Spring wiring or real service invocation with fake recommendation boundary: listBookSettingVO returns all five groups; original configured mapper initialization/insert/delete not invoked. Assert old click/new/update rank methods remain independently callable. Render PC and mobile templates with complete literal maps and metadata, plus empty groups; verify book links, PC week5/hot6 and mobile hot6, manual three groups/资讯 unchanged, no forced mobile week insertion.
- [ ] Run `HomeRecommendationIntegrationTest,HomeRecommendationTemplateTest` RED.
- [ ] Add final constructor-injected HomeRecommendationService; replace only listBookSettingVO body:

```java
return homeRecommendationService.getHome();
```

Leave unrelated ranking/search methods untouched. Old private initializer can remain unreachable from this path; never delete/reinitialize actual settings for migration.
- [ ] Run all recommendation tests plus PageController chapter executor and existing cache tests GREEN, review; user commit `feat: drive selected homepage groups from reading activity`.

### Task 5: Monitoring, safe live acceptance and full regression

**Files:** Modify Grafana overview, Prometheus alerts, performance/test-observability-config.ps1, performance/check-observability.ps1, performance/README.md, learning guide; create performance/check-home-recommendation.ps1 and its behavior test.

- [ ] Extend parsed configuration contracts for unique nonoverlapping recommendation-source/refresh/age panels. Require refresh DB error growth alert over5m and snapshot age>15min for5min. Lock runtime expected metrics/alerts and parsed provisioning. Observe RED before dashboard/rule changes.
- [ ] Add metrics and panels matching Task3 meter names exactly. Runtime query checks present series, not fabricated `or vector(0)`; age gauge describes process-local successful snapshot, not global distributed freshness.
- [ ] Implement SELECT-only live acceptance: verify homepage200 and existing map groups/caps, independent rank endpoints, Redis snapshot version/time and no duplicate within group. Compare algorithm ordering with read-only SQL using the snapshot's generatedAt window; if concurrent statistics changed, report inconclusive/retry rather than false pass. Never force-refresh by deleting keys, mutate book_setting or insert fake live reading credits.
- [ ] Behavior test replaces only external HTTP/SQL/Redis calls and proves malformed/over-cap/group-order inputs fail. Run script parse+behavior/static tests RED/GREEN and demonstrate realistic rejection mutations.
- [ ] Run full Maven `-pl novel-front -am test` with skip disabled, Node reading-heartbeat9 tests, script behaviors, staticcontract and gitdiffcheck. Explicitly report ordinary Surefire *IT exclusions. User starts monitoring frontend and runs read-only recommendation acceptance and existing reading/click regressions; promtool validates all rules.
- [ ] Review intended diff, protected YAML untouched; user commit `test: verify home reading recommendations`. No empty commits, no merging.

## Execution order and evidence

Tasks1..5 are sequential; preserve task RED/GREEN evidence and review findings in ignored local ledger. Single-instance deployment only. Validate SQL query plan and existing content-sharding compatibility before claiming resource cost; lower traffic/window does not itself guarantee performance. On live failure, stop and diagnose rather than widening scope or editing protected connection configuration.
