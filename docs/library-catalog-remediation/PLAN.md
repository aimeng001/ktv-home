# PLAN：四段式曲库自动建库与安卓点歌闭环

## 当前目标与约束
- 状态：审核和方案已交付；T1–T5 已实施，当前租约 fencing、根身份、catalog revision 和 Android 根页面刷新增量已落在未提交工作树；T6 已完成 1000 文件外部扫描烟测和 200k PostgreSQL 搜索量测。
- 从两个用户样例扩展到 20 万曲库：自动索引、歌手/拼音/语种/标签查询、就绪后点唱。
- 保留 Spring Boot/PostgreSQL、Android 统一 Controller、H5、Windows 及现有音轨语义。
- 外部曲库只读，不 rename/delete/move/overwrite，不触碰真实用户库作测试；所有扫描样本用临时目录，数据库用隔离 PostgreSQL。
- 本目录是当前任务快照；根目录 PLAN/TASK_STATE 仍记录另一轮安卓整改，不覆盖其结果。
- 新增 Java/Kotlin 类型和 V45/V46 迁移已落地；V46 回填旧路径身份并创建 catalog revision，真实部署数据库仍须按发布流程执行 Flyway。
- 每步执行 RED → GREEN → REFACTOR → REGRESSION → INTEGRATION；未得到预期失败原因前不写实现。已经正确的功能只加特征测试，不制造假失败。
- 每个步骤独立提交评审；本轮不 push、不部署、不触碰真实 NAS/部署数据库；测试与状态文档按切片提交。

## 已确认的核心问题
- E02：已有磁盘文件不因服务首次启动自动扫描。
- E03：公共库状态只有总记录数，缺索引/探测/失败解释与持久化恢复语义。
- E05：标签优先策略与“规范文件名为权威”的目标冲突。
- E04/E06：列表缺统一歌曲级就绪字段，界面缺持续状态与曲库版本失效机制。
- E07：网络故障来源未确定；E09：歌手查询性能尚未测定，禁止直接下根因结论。

## 阶段 T0：锁定业务契约和现场证据（先执行）
1. 记录 HEAD、diff、配置的 library mode/root、数据库迁移版本、Android build/version；只读检查部署，脱敏记录。
2. 查实际客户端的歌手列表请求及响应。先区分列表失败、头像失败、401/403、5xx、超时、成功空列表，再定位地址/端口/服务/数据。
3. 只读查询 songs 状态、song_files role/valid/probe_pending、song_artists 数量；与 API 对照。禁止在库未定位前重置数据库或扫真实全库。
4. 固定 4 个样本：韩红-望-国语-流行.mkv、张潼瑶-忘不掉的你-国语-流行.mkv、周杰伦-晴天-国语-流行.mkv、Beyond-海阔天空-粤语-摇滚.mkv。
5. 本轮推荐契约：配置为结构化文件名优先时，明确识别的四段元数据优先于内嵌标签；人工字段锁最高；MANAGED 及非规范文件保持旧规则。
6. 字母拼音沿用当前 multi-tap 输入，不把“数字 T9 检索”偷偷加入需求。语种与第四段标签沿用现有 API。
- 文件：复用 FilenameParserTest、KtvTransportTest；新增 LibraryCatalogContractIntegrationTest（隔离数据库/媒体）。
- RED：全新数据库仅启动且不手动扫描，应自动出现四首歌；当前启动入口缺失使其失败。先写两个样例和 wbddn 的特征测试，应直接通过。
- 完成标准：现场网络问题有证据或明确待查；“自动建库”和“可播放”验收口径固定，不互相替代。

## 阶段 T1：统一文件名与媒体元数据规则（ROOT-CAUSE FIX，已实施）
- 修改范围：library/LibraryScanService.java、library/ParsedMeta.java（仅确需区分格式来源时）、config/AppProperties.java、resources/application.yml；新增 library/LibraryMetadataResolver.java；重解析/导入调用方按 grep 结果接入；NAS Compose 仅增加明确配置。
1. 新增结构化文件名优先配置；NAS 推荐启用，其他模式保留旧默认。识别结构化格式不等于所有 recognized 文件都是四段式，至少校验非空 language/category；有歧义留待人工审核。
2. 解析器输出进入统一 resolver；按字段选择来源并记录 provenance；探测只补技术字段，不冲掉权威 title/artist/language/category。
3. 保留人工锁与人工修订；自动解析不得调用 lockMetadata 冒充人工。新增字段锁复用现有机制。
4. 在同一事务里更新歌曲身份/指纹、拼音、song_artists；复用已有合并/冲突处理。冲突可审计，不创建重复歌曲、不无条件覆盖旧记录。
5. 存量需要按 metadata policy version 回填，不能指望 size/mtime 未变的普通扫描重算全部元数据。
- RED：filename 韩红/望 + container 上传者/测试文件；探测后仍韩红/望；legacy 模式仍按旧优先级；人工锁不被覆盖；同一数据经 Fast Index/probe/reparse 后一致。
- GREEN：只提取元数据选择规则并接入现有写路径，不重写扫描器。
- REFACTOR：消除本次新增的规则重复，保持来源和锁逻辑可读。
- REGRESSION：FilenameParserTest、IncrementalLibraryScanTest、CollaborativeArtistWritePathTest、现有重解析/导入/合并测试。
- 完成标准：全流程语义不漂移、拼音和歌手关联一致、冲突显式、无额外 FFprobe/Hash/NAS 写入。

## 阶段 T2：首次启动自动建库与恢复（ROOT-CAUSE FIX，已实施）
- 修改范围：新增 library/LibraryBootstrapService.java、library/LibraryScanCoordinator.java、library/LibraryScanStateStore.java；LibraryScanService/LibraryWatchService/AdminScanController 接同一调度入口；新增 db/migration/V45__library_scan_state.sql（实施时校号）及对应测试。
1. 先持久化最小状态：logical library identity、规范化 root、mode、policy version、scan id、state/phase、heartbeat、last successful scan、错误码；进度定期批量写入，不每首歌更新一次任务行。
2. ApplicationReadyEvent 仅提交任务，不同步等 20 万文件或 FFprobe；仅外部只读模式自动索引。识别“从未成功扫描”“上次 PARTIAL/FAILED/中断”“策略版本变化”。
3. 不用 songs.count()==0 作唯一触发条件：MANAGED 数据、旧根目录、部分扫描均可能非零；成功扫描空库也要保存成功标记，防启动死循环。
4. 启动、手动、监听统一互斥入口；保留 scanRunning 进程互斥，并给持久化任务原子 owner/scanId 认领。故障接管用租约 + heartbeat + fencing；旧 owner 不得把新任务写成完成。当前工作树已将心跳与批事务 ownership check 接入协调入口，失效任务不发布成功回调。
5. 复用 scanAllInternal 两阶段、每批 500 和探测并发 2。先保持现有枚举后探测顺序；流水化并发另作性能决策。
6. 恢复采用安全重枚举 + 已有 size/mtime/identity/sidecar 快照去重 + pending 队列；不用无序目录的 lastRelativePath 直接跳过前半库。
7. 正常成功启动是否重枚举必须明确：本阶段成功同版本无需重扫；Watch 关闭时停机新增文件仍需手动扫描，若要求每次启动发现变化，增加独立轻量 reconcile-on-start 策略及测试，不能声称已经覆盖。
8. 根目录变化先校验路径和库身份，不自动混合两个库或全量标缺失；挂载恢复和 partial scan 延用现有安全规则。无法证实挂载身份时显示 UNKNOWN，不谎报 mounted=true。
9. 未挂载/无权限状态可启动 API，暴露故障；后台有限退避重试，不靠抛异常把整个服务拉死。避免 20 万文件预统计后再遍历一遍。
- RED：空库自动扫描；已有部分索引仍恢复；重复启动事件+管理员点击+Watch 只运行一个；租约过期接管；旧 worker 完成无效；空库成功不循环；executor 拒绝后可重试；无权限不标全库丢失；MANAGED 不进入外部自动扫描。
- GREEN：复用现有扫描执行器和路径检查；新增生命周期/持久化，不新建另一条入库链。
- REGRESSION：LibraryWatchServiceTest、IncrementalLibraryScanTest、ExternalReadOnlyLibraryTest、ExternalLibraryPermissionScanTest、LibraryScanMemoryLifecycleTest。
- 完成标准：空库可自动启动索引；重启可恢复；无变化文件 probe/hash/dbUpdates 指标不退化；真实 NAS 权限仍待单独验收。

## 阶段 T3：公共状态与歌曲就绪契约（ROOT-CAUSE FIX，已实施）
- 修改范围：web/LibraryStatusController.java、新增 library/LibraryStatusService.java、repo/SongRepository.java/SongFileRepository.java、web/dto/SongDto.java；SongController、CategoryBrowseService 以及其它 SongDto.from 调用方；保留兼容构造器或集中 mapper。
1. 保留 totalSongs；增加 libraryMode/rootState/scanState/phase/discoveredFiles/indexedFiles/readySongs/probePendingFiles/catalogRevision/statusRevision/errorCode/updatedAt。
2. indexedSongs/readySongs 为 distinct song_id；indexedFiles/discoveredFiles/probePendingFiles 为文件数，字段名区分。探测完成数不是就绪歌曲数。枚举未结束 discovered 是“已发现”，不是已知最终总量。
3. rootState=READABLE/NOT_FOUND/NOT_READABLE/UNKNOWN；能访问目录不等于挂载已验证。客户端无法请求状态时独立显示 OFFLINE，服务端不靠 serverReady=false 描述断网。
4. 新增列表 playable/unavailableReason，按 SongAvailabilityPolicy.playableSongIds 批量计算；不能按 duration>0/status=ok/mediaType!=pending 自行另造规则。
5. 页面一批 50 首只增一条 readiness 查询；遍历单首 isPlayable 会 N+1，禁止采用。
6. catalogRevision 只在已提交的目录数据变化时更新；statusRevision 反映任务状态变化。扫描末尾批量递增 revision，人工元数据/音频语义写入按事务递增；扫描进度不可每首触发目录整页刷新。摘要计数集中计算和缓存，不能每个客户端高频多次 COUNT 全表。
7. 公共 DTO 不泄漏 root 绝对路径、原始异常、SQL/凭据；管理员仍可查详细诊断。JSON 只增字段，旧 totalSongs 和旧端调用继续工作。
- RED：旧响应兼容；状态重启恢复；多文件一歌只计一次 ready；pending 可见但不可点；批量 query 次数不随页大小增加；原始异常不进公共响应。
- REGRESSION：LibraryStatusControllerTest、SongAvailabilityPolicyTest、SongDtoArtistAvatarTest、CategoryBrowseControllerTest、SongController 相关测试及 H5/Windows JSON 反序列化。
- 完成标准：状态计数定义明确且一致；列表、队列、流入口 readiness 无分歧。

## 阶段 T4：安卓点歌状态与刷新（ROOT-CAUSE FIX，已实施）
- 修改范围：net/SongApi.kt/Models.kt；新增 net/LibraryApi.kt（或复用现有网络层扩展）；controller/ControllerState.kt/ControllerViewModel.kt；ui/KtvKioskOverlayController.kt/KtvKioskSongAdapter.kt；控制器相应列表适配器及实际布局文件。
1. 使用现有 KtvTransport 和服务器 session scope，不另建 OkHttp 单例/地址存储。
2. 状态模型至少支持 LOADING、OFFLINE、HTTP_ERROR、ROOT_UNAVAILABLE、SCANNING、EMPTY、FILTER_EMPTY、READY、PARTIAL_FAILURE；有列表内容时可叠加扫描/故障条幅。
3. 非空成功不能永久缓存：以 serverSession + filters + catalogRevision 作为缓存失效依据；修改 artistsLoaded 等标志，避免部分索引结果永久保留。
4. 首次进入、有效库版本变化、网络恢复、用户重试触发刷新；只在 STARTED 且相关页可见时轮询；示例 3 秒/退避上限 30 秒，实际可配置。离开页面或切服务器取消任务。
5. 同时保留 request generation / current query 判定；版本变化后清第一页再加载，避免扫描改变排序时跨版本翻页漏项/重复。
6. 错误按 UiDomain.CATALOG/SEARCH 隔离，头像失败不能覆盖歌手列表成功；Toast 作为补充，主体上有可见原因和重试按钮。
7. pending 歌曲显示“准备中”，禁用普通点唱及置顶；服务端仍二次校验，处理列表到点击之间状态变化。没有 playable 字段的旧服务端走明确兼容分支，不能误报库已就绪。
8. 默认“全部”保持；未知性别不被隐藏；不要把组合分类逻辑扩大成一套新的识别器。
- RED：网络失败与成功空列表不同；头像失败不清空目录；scanning→ready 自动刷新；部分列表增长；同筛选旧请求迟到不覆盖新版本；离开页面停止轮询；同一时间仅一个状态任务；待就绪按钮不发写请求。
- REGRESSION：KtvTransportTest、ControllerStateDomainErrorTest、ControllerStateReducerTest、现有 KioskCatalogActionRouter/键盘/分页/适配器测试；Android 单元测试和 assembleDebug。
- 完成标准：CONTROLLER/COMBINED 对应入口都可解释状态并刷新；真机/遥控器/触摸另验。

## 阶段 T5：存量修复与纵向闭环（ROOT-CAUSE FIX，核心路径已实施）
- 修改范围：复用 SongReparseService/ArtistCreditService，新增需要的 metadata-only backfill 服务和隔离集成测试；仅在既有能力不足时扩展管理员预览/执行入口。
1. 存量先预览：按当前 root 和候选策略分页统计将改的 title/artist/language/category/拼音/关联、人工锁跳过和指纹冲突。
2. 默认不自动批量覆盖人工锁、未知来源标签、跨根数据；旧 tags 混合 AI/人工，不能当全部第四段字段。
3. 游标按稳定 id 分页，每批独立事务，策略版本可恢复；元数据修复不读全媒体、不重新 FFprobe/Hash。
4. 4 首标准媒体从全新 PostgreSQL 自动扫描；锁住 FFprobe 时检查“能查、不能入队”；释放后检查各接口、入队和 Range 流。
5. 四首均 ready 时：歌手各 1；国语 3/粤语 1；流行 3/摇滚 1；hh/hanhong/韩红、wbddn/wangbudiaodeni/忘不掉的你命中；不依赖头像/在线 AI。
6. 合作歌手一首歌可出现在多个歌手目录；多文件同歌曲不使歌手计数重复；统计断言以数据库最终逻辑歌曲为准。
7. 重扫不改变数量；新增/文件名变更/丢失/重挂载/权限失败/中断恢复分别检查 DB 与目录一致，NAS 源文件内容和路径不变。
- RED：有冲突容器标签和旧错拼音的存量用例；重复执行不漂移；同名不同媒体保留现有合并语义；4 首自动建库纵向失败。
- REGRESSION：受影响模块全部现有测试，H5/Android/Windows/scripts/Compose；Dockerless 结果单列，不能代替 PostgreSQL Testcontainers。
- 完成标准：自动化集成与真机播放两道门分别通过；缺现场时明确标待验，不宣布本轮业务完工。

## 阶段 T6：20 万曲库性能验收（已完成初始量测，优化条件执行）
- 修改范围：优先扩展现有 SearchLargeLibraryPerformanceTest/LargeExternalLibraryScanTest；新增 PublicArtistDirectoryPerformanceTest。只有失败证据支持时修改 SongRepository 或增加投影迁移。
1. 采用 20 万歌曲及真实分布歌手数、合作歌手、极热门歌手、未知资料；分别测冷启动/热缓存、30 首歌手页、字母/性别筛选、详情分页、1/2/多字拼音。
2. 使用 PostgreSQL EXPLAIN (ANALYZE, BUFFERS)，记录执行/计数查询、返回大小、RSS/heap、连接数、FFprobe/Hash 次数；不以 H2 证明 PostgreSQL 查询。
3. 建议验收目标（待参考硬件确认，不是已测事实）：局域网热 API p95 ≤500ms；单歌手页最大 100；扫描服务内存不随 20 万文件线性积累；无变化重扫媒体 probe/hash=0，业务无意义 UPDATE=0；实际扫描耗时先建立基线。
4. 首选调整查询和已有索引，成本小、实时一致；若仍不达标，再采用可重建的 artist_catalog/catalog_facets目录投影。
5. 投影以 DISTINCT(song_id, artist_key) 或重算受影响 key 保证幂等；删除、合并、状态、歌手改名、资料修改均纳入变更源。新数据提交与投影更新原子一致，或使用持久化 outbox+版本对账；不使用裸 +1。
6. 重建先写影子版本、校验总量/去重/分页，再切换，禁止删除旧目录后让客户端等待；记录 freshness，允许回退旧查询。
7. genre/source_genre 仅在明确产品语义和来源回填策略后独立迁移，暂不作为本轮强制条件。
- 当前证据：SearchLargeLibraryPerformanceTest 已在 200,000 行 PostgreSQL 上通过，宽匹配单次约 1.1s；尚未形成硬件校准后的 p95 门槛，不据此直接改查询。
- 完成标准：保存测量报告和环境说明；性能未达标不能靠删 count、缩小样本、跳过测试假通过。

## 方案选择
| 决策 | 推荐 | 原因/代价 | 不采用的替代 |
|---|---|---|---|
| 首次扫描 | 独立 Bootstrap + 共用协调入口 | 生命周期清楚，保留扫描能力 | 开启 Watch 替代首扫会漏已有文件 |
| 文件元数据 | 显式结构化文件名策略 + 人工锁 | 契合规范库，又不改变所有旧库 | 全局强制 filename 覆盖会破坏其他导入 |
| 媒体上线 | 可浏览与可点唱分开 | 遵守现有音轨/串流规则 | 取消 probePending 门禁有播放风险 |
| 目录性能 | 测量→最小索引/查询→条件投影 | 避免提前增加一致性成本 | 每首裸计数 +1 不可重试 |
| 风格 | 先复用 tags | 兼容客户端和存量 | 当前直接新建 genre 会扩范围 |
| 恢复 | 安全重枚举、快照去重 | 不依赖文件系统遍历顺序 | lastRelativePath 断点可漏文件 |

## 完成门禁与执行顺序
- 顺序：T0 → T1 → T2 → T3 → T4 → T5 → T6；当前已完成代码切片和初始量测，现场网络/设备/真实 NAS 门禁仍待执行。
- 每批记录 RED 失败原因、GREEN 结果、回归命令/计数、未验项；出现范围扩大或根因被推翻立即更新本快照。
- G1：后端全量 726 tests 通过、0 failures、0 errors、4 skipped；Flyway 空库与 V24 升级均验证到 V46。G2：1000 文件 Testcontainers 外部扫描通过，四首标准样本和存量 PostgreSQL 纵向集成仍待执行；G3：Android testDebugUnitTest 与 assembleDebug 通过，UI 仪器测试待设备。
- G4：真实 20 万 NAS 上只读扫描与性能通过；G5：真实 Android 选择四首并播放，队列/音轨/进度/Range 正确；H5/Windows 未回归。
- G4/G5 不能由 mock、空文件、单元测试、截图或 APK 构建替代。
- 交付文档完成不等于整改已实施。当前状态及测试证据以 TASK_STATE.md 为准。

