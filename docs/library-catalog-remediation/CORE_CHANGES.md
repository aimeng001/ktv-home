# 核心修改代码与 TDD 验证草案

本文件是实施设计，不是已经应用的补丁。除“本轮验证”外，所有新类型、新字段、新 SQL、新测试均尚未加入业务工程、编译或执行。
路径相对 D:\mimocode\ktv-home；现有方法名已核查，新类型标为“拟新增”。实现前重新检查方法调用方与字段锁/指纹合并规则。

## A. 先固定能运行的现有解析契约（特征测试，不伪称 RED）

拟追加到 backend/src/test/java/com/homektv/library/FilenameParserTest.java：

~~~java
@Test
void parsesUserLibraryExamples() {
    ParsedMeta a = FilenameParser.parse("韩红-望-国语-流行.mkv");
    ParsedMeta b = FilenameParser.parse("张潼瑶-忘不掉的你-国语-流行.mkv");

    assertThat(a.recognized()).isTrue();
    assertThat(a.artist()).isEqualTo("韩红");
    assertThat(a.title()).isEqualTo("望");
    assertThat(a.language()).isEqualTo("国语");
    assertThat(a.category()).isEqualTo("流行");

    assertThat(b.recognized()).isTrue();
    assertThat(b.artist()).isEqualTo("张潼瑶");
    assertThat(b.title()).isEqualTo("忘不掉的你");
    assertThat(b.language()).isEqualTo("国语");
    assertThat(b.category()).isEqualTo("流行");

    assertThat(PinyinUtil.fullPinyin(b.title())).isEqualTo("wangbudiaodeni");
    assertThat(PinyinUtil.initials(b.title())).isEqualTo("wbddn");
    assertThat(PinyinUtil.initials(b.artist())).isEqualTo("zty");
}
~~~

这组断言按现有代码应通过。真正的 RED 是“启动后自动入库”和“探测后仍保持文件名身份”，不是让现有解析器故意变红。

## B. 元数据选择：纯策略 + 现有事务写路径

拟新增 backend/src/main/java/com/homektv/library/LibraryMetadataResolver.java。
以下完整的纯策略骨架只负责单字段选源；source 标记使用现有 provenance 风格。manualLocked 由现有 Song 字段锁读出，不能从字符串来源猜测。

~~~java
package com.homektv.library;

import java.util.Objects;

public final class LibraryMetadataResolver {
    private LibraryMetadataResolver() {}

    public record Value(String text, String source) {
        public Value {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(source, "source");
        }
    }

    public record Candidates(
            boolean manualLocked,
            Value current,
            boolean structuredFilenamePreferred,
            Value structuredFilename,
            Value legacyResolved) {
    }

    public static Value resolve(Candidates input) {
        Objects.requireNonNull(input, "input");
        if (input.manualLocked()) {
            // 人工清空也是合法显式决定，不用自动来源填回去。
            return Objects.requireNonNull(input.current(), "locked current value");
        }
        if (input.structuredFilenamePreferred()) {
            Value file = input.structuredFilename();
            if (file != null && !file.text().isBlank()) return file;
        }
        // legacyResolved 由旧的 audio/container/lrc/filename 规则生成。
        return Objects.requireNonNull(input.legacyResolved(), "legacyResolved");
    }

    public static boolean isStructured(ParsedMeta parsed) {
        return parsed != null && parsed.recognized()
                && !parsed.artist().isBlank()
                && !parsed.title().isBlank()
                && !parsed.language().isBlank()
                && !parsed.category().isBlank();
    }
}
~~~

RED/GREEN 所需的策略测试：

~~~java
@Test
void structuredFilenameWinsOverContainerTag() {
    var resolved = LibraryMetadataResolver.resolve(
            new LibraryMetadataResolver.Candidates(
                    false, null, true,
                    new LibraryMetadataResolver.Value("望", "filename"),
                    new LibraryMetadataResolver.Value("测试文件", "container_tag")));
    assertThat(resolved.text()).isEqualTo("望");
    assertThat(resolved.source()).isEqualTo("filename");
}

@Test
void lockedManualValueWinsOverFilename() {
    var manual = new LibraryMetadataResolver.Value("望（现场版）", "manual");
    var resolved = LibraryMetadataResolver.resolve(
            new LibraryMetadataResolver.Candidates(
                    true, manual, true,
                    new LibraryMetadataResolver.Value("望", "filename"),
                    new LibraryMetadataResolver.Value("测试文件", "container_tag")));
    assertThat(resolved).isEqualTo(manual);
}
~~~

仅策略测试变绿不算 T1 完成，还必须在现有 IncrementalLibraryScanTest 中模拟有冲突的 MediaProbe：
1. 临时四段式文件进入 Fast Index；
2. 用 CountDownLatch 暂停 probe，断言数据库保存韩红/望与 pending；
3. probe 返回上传者/测试文件，释放 latch；
4. 再读最终 Song、拼音与 SongArtist，仍为韩红/望/hh/w，provenance 为 filename；
5. 旧模式保持原标签优先，锁定字段保持人工值；
6. 多文件合并和 fingerprint 冲突沿用现有规则。

接入 LibraryScanService.java:1517–1541 附近时，先保留旧规则求 legacyResolved，再为四段字段调用 resolver；保留后续音轨、时长和分类计算。不要拿“新建 Song 直接 save”替换已有合并逻辑。
SongReparseService 会 lockMetadata，这是人工动作；后台自动回填不调用该人工锁路径。

## C. 扫描状态持久化与原子认领

拟新增 V45__library_scan_state.sql；当前最高 V44，开工时再次分配版本。本表是扫描任务状态，不是第二套歌曲表。
第一阶段仍是单一活动曲库。library_key 是稳定逻辑标识，不能只凭 root 路径字符串当作物理 NAS 身份；旧数据根归属不确定时阻止自动迁移并报告 ROOT_IDENTITY_UNVERIFIED。

~~~sql
CREATE TABLE library_scan_state (
    library_key             text PRIMARY KEY,
    configured_root         text NOT NULL,
    library_mode            text NOT NULL,
    metadata_policy_version integer NOT NULL,
    scan_id                 uuid,
    owner_id                uuid,
    generation              bigint NOT NULL DEFAULT 0,
    state                   text NOT NULL DEFAULT 'IDLE',
    phase                   text,
    heartbeat_at            timestamptz,
    started_at              timestamptz,
    finished_at             timestamptz,
    last_successful_scan_at timestamptz,
    discovered_files        bigint NOT NULL DEFAULT 0,
    indexed_files           bigint NOT NULL DEFAULT 0,
    probe_completed_files   bigint NOT NULL DEFAULT 0,
    error_code              text,
    CHECK (state IN ('IDLE','RUNNING','COMPLETED','PARTIAL','FAILED','INTERRUPTED')),
    CHECK (discovered_files >= 0 AND indexed_files >= 0 AND probe_completed_files >= 0)
);
~~~

认领是一个数据库条件更新，不是先 SELECT 再无条件 UPDATE：

~~~sql
-- 参数由 store 绑定，lease_seconds 为服务端受控正整数。
UPDATE library_scan_state
SET owner_id = :owner_id,
    scan_id = :scan_id,
    generation = generation + 1,
    state = 'RUNNING',
    phase = 'DISCOVERING',
    heartbeat_at = clock_timestamp(),
    started_at = clock_timestamp(),
    finished_at = NULL,
    discovered_files = 0,
    indexed_files = 0,
    probe_completed_files = 0,
    error_code = NULL
WHERE library_key = :library_key
  AND configured_root = :validated_root
  AND library_mode = 'EXTERNAL_READ_ONLY'
  AND (
      state <> 'RUNNING'
      OR heartbeat_at < clock_timestamp()
           - make_interval(secs => :lease_seconds)
  )
RETURNING scan_id, generation;
~~~

使用规则：
- 初始化行与认领在明确事务中执行；初始 INSERT ON CONFLICT DO NOTHING。
- “是否需要启动”先由 policy 判断，不能所有 state<>RUNNING 都自动扫。
- 心跳、完成和失败更新必须带 library_key+owner_id+scan_id+generation 条件，更新 0 行表示已失去执行权。
- 仅完成状态 fencing 不足以阻止旧 worker 写歌曲。每个写库批次先锁住状态行并确认当前 generation，在同一事务里写 songs/files/credits；旧 worker 不得继续提交新批次。数据库不可达时不能假定仍持有租约。
- 长 FFprobe 不持有数据库事务；完成后落库批次重新校验 owner。超时/取消可追踪，关闭停止执行器。
- 成功空库也写 last_successful_scan_at；PARTIAL/FAILED 不覆盖最近成功时间。
- 目录未完整枚举时不能批量标丢失；复用现有 enumerationComplete 分支。
- 此结构没有假定“保存 lastRelativePath 就能安全恢复”。

Bootstrap 核心入口（拟新增类的方法片段；构造注入省略，不是完整可编译补丁）：

~~~java
@EventListener(ApplicationReadyEvent.class)
public void onReady() {
    if (!LibraryModePolicy.isExternalReadOnly(properties)) return;
    coordinator.requestBootstrap();
}
~~~

coordinator.requestBootstrap 的契约：
- ApplicationReadyEvent 线程只入有界任务队列；
- 在后台校验根目录/状态/策略版本并原子认领；
- 调用复用的现有扫描执行路径；
- executor 拒绝时写可重试状态，有限退避；不能吞异常留下永远 RUNNING；
- scanAll/startScan、手动和 Watch 不绕过协调器；不能“先设置 scanRunning 再调用 startScan”导致自锁；
- Watch 同步结果/通知与 AdminScanController 响应需保留原行为或显式兼容适配。

## D. 自动扫描的 RED 必须验证最终业务状态

拟新增 LibraryBootstrapIntegrationTest；以下是测试场景伪代码，fixture/probeGate/await 等需按项目测试框架实现，未宣称已有这些 helper。

~~~java
@Test
void bootsAnExistingLibraryWithoutManualScan() {
    // fixture: 隔离 PG，独立临时 source/data，EXTERNAL_READ_ONLY，watch=false
    // Spring 启动前预先放好 4 个合法名称样本。
    fixture.createFourNamedMediaFiles();
    probeGate.pause();
    fixture.startApplication();

    await().untilAsserted(() -> {
        assertThat(db.songCount()).isEqualTo(4);
        assertThat(api.artists("")).extracting("name")
                .containsExactlyInAnyOrder("韩红", "张潼瑶", "周杰伦", "Beyond");
        assertThat(api.search("wbddn")).extracting("title")
                .contains("忘不掉的你");
    });

    // 此时“已能浏览”不等于“可点唱”。
    assertThat(api.song("望").playable()).isFalse();
    assertThat(api.enqueue("望").errorCode()).isEqualTo("SONG_NOT_READY");
    assertThat(db.queueCount()).isZero();

    probeGate.releaseWithValidResults();
    await().untilAsserted(() -> {
        assertThat(api.status().readySongs()).isEqualTo(4);
        assertThat(api.song("望").playable()).isTrue();
    });

    assertThat(api.enqueue("望").isSuccess()).isTrue();
    assertThat(db.queueSongTitle()).isEqualTo("望");
}
~~~

注意：
- Mock FFprobe + 文本文件只能验证入库/查询/队列契约，不得验证真实媒体播放成功。
- 另一条外部验收使用真实短 MKV、实际 FFprobe 和 Android；播放检查文件/Range/音轨，不能只断言 HTTP 200。
- await 用超时轮询和可观测 latch，不用固定 sleep 掩盖竞态。
- 空数据库和临时路径必须隔离，不使用真实部署 PostgreSQL。

## E. 公共状态 DTO 与批量就绪映射

拟新增 PublicLibraryStatus record（字段草案）：

~~~java
public record PublicLibraryStatus(
        long totalSongs,             // 保持旧定义：数据库总歌曲数
        String libraryMode,
        String rootState,            // READABLE / NOT_FOUND / NOT_READABLE / UNKNOWN
        String scanState,            // IDLE / RUNNING / COMPLETED / PARTIAL / FAILED / INTERRUPTED
        String phase,
        long discoveredFiles,        // 本轮已发现，未必是最终总量
        long indexedFiles,           // 本轮完成快速索引的文件数
        long indexedSongs,           // 当前可归属库的去重歌曲数
        long readySongs,             // 当前可点唱的去重歌曲数
        long probePendingFiles,
        long catalogRevision,
        long statusRevision,
        String errorCode,
        java.time.OffsetDateTime updatedAt) {
}
~~~

rootState=UNKNOWN/根身份不明时，当前库统计应使用 nullable 值或 explicit countsKnown 字段，而不是输出 0 伪装成空库；正式 DTO 在 T3 的契约测试中确定。totalSongs 仍保留旧值。
catalogRevision 必须来自所有影响目录的已提交写入；若改用集中 snapshot 修订号，同样要保证人工资料修改、删除、修复能使其失效。

列表映射复用已有 batch API；拟新增 mapper 核心：

~~~java
public List<SongDto> toListItems(List<Song> page) {
    Set<Long> playableIds = availabilityPolicy.playableSongIds(page);
    return page.stream()
            .map(song -> SongDto.from(song)
                    .withAvailability(
                            playableIds.contains(song.getId()),
                            playableIds.contains(song.getId())
                                    ? null : "SONG_NOT_READY"))
            .toList();
}
~~~

withAvailability 为拟新增 SongDto 方法，需同时扩充 record 的 boolean playable/String unavailableReason，并保留原构造器兼容。不能复制代码后忘记添加字段。
对需要区分“准备中/媒体失效/待审核”的界面，可从批量文件状态投影生成更细 unavailableReason；上述最小版本只承诺不可点唱，不把所有失败都解释成正在扫描。
全仓 grep SongDto.from 调用方，覆盖搜索、分类、新歌、推荐、歌单、队列等；旧端忽略新增字段，新端对旧响应明确兼容。

就绪计数 SQL 示例（仅根身份已验证且该 active_role 归属唯一活动库时适用）：

~~~sql
SELECT count(DISTINCT s.id)
FROM songs s
WHERE lower(s.status) = 'ok'
  AND EXISTS (
      SELECT 1
      FROM song_files f
      WHERE f.song_id = s.id
        AND f.file_role = :active_role
        AND f.valid = true
        AND f.probe_pending = false
        AND f.media_type IS NOT NULL
        AND trim(f.media_type) <> ''
        AND lower(trim(f.media_type)) <> 'pending_probe'
  );
~~~

不能把 role 当多根目录隔离键。若未来允许多个外部库并存，应单独迁移 library_id 关联；本阶段对根身份变化停止自动合并。摘要计数与现有 policy 用同一套真值测试防止语义漂移。

## F. Android：持续展示与目录失效

以下是拟新增纯展示策略片段，不要求重构全部 ControllerState。现有 UiDomain.CATALOG/SEARCH 保留：

~~~kotlin
sealed interface CatalogBody {
    data object Loading : CatalogBody
    data object Empty : CatalogBody
    data object FilterEmpty : CatalogBody
    data class Failure(val message: String, val retryable: Boolean) : CatalogBody
    data class Content(val hasItems: Boolean, val scanning: Boolean) : CatalogBody
}

data class CatalogCacheKey(
    val serverSession: String,
    val gender: String,
    val initial: String,
    val revision: Long,
)

// 网络状态由客户端请求结果产生，不能指望断网时从服务端取 serverReady=false。
fun catalogBody(
    loading: Boolean,
    errorMessage: String?,
    hasItems: Boolean,
    scanning: Boolean,
    filtersActive: Boolean,
): CatalogBody = when {
    hasItems -> CatalogBody.Content(hasItems = true, scanning = scanning)
    errorMessage != null -> CatalogBody.Failure(errorMessage, retryable = true)
    loading || scanning -> CatalogBody.Loading
    filtersActive -> CatalogBody.FilterEmpty
    else -> CatalogBody.Empty
}
~~~

此最小策略还需外层状态条区分 ROOT_UNAVAILABLE/PARTIAL、加载中/正在建库及不可重试错误；不把所有故障设 retryable=true 上线。Content 存在时 error 在条幅继续显示，不能被有数据分支吞掉。
替换 KtvKioskOverlayController 中 artistsLoaded 的永久成功缓存；缓存 key 变化后刷新第一页，并用 generation 阻止旧请求覆盖新数据。

状态获取放在已有生命周期与 session scope 中：

~~~kotlin
// Activity/Fragment 的片段；owner/viewModel 为现有生命周期对象与拟扩展方法。
owner.lifecycleScope.launch {
    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.observeVisibleCatalogStatus()
    }
}
~~~

observeVisibleCatalogStatus 必须保证：相关页不可见时不轮询、至多一个 job、切服务器取消、按版本合并刷新、网络失败退避、不在主线程做 IO。
SongAdapter 依据新增 playable 控制按钮与提示；点击后仍显示服务端 SONG_NOT_READY，不能乐观造入队成功。
测试用 coroutine test scheduler 推进虚拟时间，验证离开页面后请求次数不再增加，而不是测试字符串是否包含某个方法名。

## G. 条件性能优化：禁止裸计数 +1

若 T6 证实目录聚合未达标，先选用精确重算受影响 artist_key；本节是拟新增投影 SQL，不是现有 schema：

~~~sql
-- transaction 内，先串行化同一 artist_key 的重算（行锁或等价机制）。
-- member_set 必须与现有 credits UNION/legacy fallback 语义一致。
INSERT INTO artist_catalog(artist_key, song_count, updated_at)
SELECT :artist_key, count(DISTINCT song_id), clock_timestamp()
FROM affected_artist_members
WHERE artist_key = :artist_key
ON CONFLICT (artist_key) DO UPDATE
SET song_count = EXCLUDED.song_count,
    updated_at = EXCLUDED.updated_at;
~~~

affected_artist_members 是设计上的视图占位，实施时需要定义，与现有 SongRepository credits 规则严格一致，不是可立即执行的现成表。
改名重算 oldKey/newKey，删除时清零/移除无成员行；gender/avatar 仍从 artist_profiles 读取或受版本失效控制。计数“已索引”和“可点唱”不能混用。
影子表重建 + 版本切换 + 基表对账优于全表清空重算；若查询/索引优化已经满足目标，不建立这套投影。

## H. 命令与验收证据

以下为 PowerShell 7。mvn.cmd 路径是本机本轮已实际使用的 Maven 3.9.11；换机器需重新定位。离线 -o 仅在依赖已缓存时使用。

本轮已运行：

~~~powershell
Set-Location 'D:\mimocode\ktv-home\backend'
$mavenExe = 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11\a2d47e15\bin\mvn.cmd'
& $mavenExe -o '-Dmaven.repo.local=D:\mimocode\ktv-home\.maven-local' '-Dtest=FilenameParserTest,SongAvailabilityPolicyTest,CategoryBrowseServiceTest,SongSearchServiceTest,LibraryWatchServiceTest,LibraryStatusControllerTest' test
& $mavenExe -o '-Dmaven.repo.local=D:\mimocode\ktv-home\.maven-local' '-Dtest=IncrementalLibraryScanTest,ExternalLibraryPermissionScanTest,CollaborativeArtistWritePathTest' test
~~~

本轮结果：第一组 47/0/0/0；第二组 54/0/0/1（tests/failures/errors/skipped）。实际通过 100。
跳过原因：文件系统未暴露替换文件的新 identity，触发原有 Assumption；不修改该测试让它假通过。

以下是实施时的计划命令，本轮未执行：

~~~powershell
Set-Location 'D:\mimocode\ktv-home'
python scripts/full_test_runner.py --backend local --backend-parallelism 8
python scripts/full_test_runner.py --backend all --backend-parallelism 8

Set-Location 'D:\mimocode\ktv-home\android-tv'
$env:GRADLE_USER_HOME = 'D:\mimocode\ktv-home\.gradle-local'
.\gradlew.bat testDebugUnitTest assembleDebug

Set-Location 'D:\mimocode\ktv-home\backend'
& $mavenExe '-Dmaven.repo.local=D:\mimocode\ktv-home\.maven-local' '-Dtest=LargeExternalLibraryScanTest' '-DrunLargeLibraryScanTest=true' '-DscanRows=200000' test
& $mavenExe '-Dmaven.repo.local=D:\mimocode\ktv-home\.maven-local' '-Dtest=SearchLargeLibraryPerformanceTest' '-DrunSearchPerformanceTest=true' '-DsearchLibraryRows=200000' test
~~~

条件：
- 大曲库命令需要可用 Docker/Testcontainers 和足够磁盘/内存；扫描测试生成临时文件，媒体探测是 fake。
- searchMaxMillis 可用于额外上限，但单次 elapsed 上限不是 p95；正式 T6 另做重复采样并标记冷/热条件。
- --backend local 明确排除了容器集成测试，不等于全量通过。
- 新增 PublicArtistDirectoryPerformanceTest、LibraryBootstrapIntegrationTest 等需实现后单独运行，不能现在将不存在的类写成已通过。
- PostgreSQL 迁移需用新库和升级库各跑一次，回滚应用前确认新增字段/DTO兼容；不建议无备份执行破坏性 down migration。

## I. 最终真实验收清单

1. 使用隔离部署和真实可播放四首 MKV，数据库为空；无需管理员手动扫描。
2. 先出现扫描进度和可浏览元数据，pending 禁止入队；逐首 probe 完成后按钮开放。
3. 歌手、首字母/全拼、语种、标签得到同一 song_id；韩红/望、张潼瑶/忘不掉的你正确，wbddn 命中。
4. Android/TV 实际点唱，队列只增加一条，媒体来源是对应文件，HTTP Range 正常，进度/伴唱切换不回到开头。
5. 中途断网、重启服务、恢复挂载后状态明确；不会“永久加载中”或把故障显示成空库。
6. NAS 文件路径、大小、mtime、抽样内容校验未改变；测试不能对 20 万媒体执行不必要的全量 Hash。
7. 四首通过再扩 2 千/2 万/20 万；记录硬件、网络、媒体分布、吞吐和 p95。真机播放与合成性能结果分开报告。

