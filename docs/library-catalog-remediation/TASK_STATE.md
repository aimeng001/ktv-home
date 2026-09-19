# TASK_STATE：曲库闭环观点审核与 TDD 实施

## 当前阶段
- 用户已确认按计划执行；T0 证据审核和整改计划已经交付，T1–T5 业务切片已实施，T6 已完成可执行的 Docker/Testcontainers 量测。
- 目标项目为 D:\mimocode\ktv-home；本状态快照位于 docs/library-catalog-remediation/，不覆盖项目根目录既有 PLAN.md/TASK_STATE.md。
- 当前源码提交：fd3a874（T1–T5 实现、Android 状态闭环、Bootstrap 纵向测试修正）。

## 已完成切片
- T1 0ff2f9c：新增 LibraryMetadataResolver 和结构化文件名优先策略；配置打开时，明确的四段式文件名可覆盖容器标签，人工锁定字段仍优先；拼音、来源和歌手关联沿同一写入路径更新。
- T2 dffefca：新增 V45 library_scan_state、租约/世代 fencing、LibraryScanCoordinator 和 ApplicationReadyEvent Bootstrap；启动、管理员手动扫描和 Watch 共用外部只读调度入口。
- T3 0178096：新增公共曲库状态服务/接口、distinct 歌曲与 ready 文件计数、catalogRevision/statusRevision、歌曲级 playable/unavailableReason；列表、播放相关入口批量套用 SongAvailabilityPolicy，避免逐首 N+1。
- T4 509e7f6：Android 新增曲库状态模型、/library/status API、可见页轮询/版本失效、scanning→ready 自动刷新、离线/根目录故障/空结果提示；待探测歌曲显示“准备中”并禁止点唱。
- T5 34cf69e：SongReparseService 尊重 title/artist 人工锁定，重解析不再以文件名或容器标签覆盖人工身份，并以有效身份重建指纹和歌手关联。
- T6 测试切片 fd3a874：纵向测试等待生产 Bootstrap 完成，使用合法四段式样本，并验证首次探测及无变化重扫不再探测。
- 整改文档：REVIEW.md、PLAN.md、CORE_CHANGES.md、VALIDATION.json。

## 根因与证据
- 原始“歌手页为空”观点的方向成立，但源码证据指向“索引生命周期、状态表达和就绪门禁未闭环”，不是缺少一个歌手 UI。
- 原实现没有可靠的首次启动自动扫描状态；T2 将其改为持久化状态加单一协调入口。
- 文件名四段解析已经存在，但标签/重解析路径可能覆盖结构化元数据；T1/T5 分别统一来源选择和人工锁边界。
- Fast Index 的候选歌曲可以先进入数据库，但 PENDING_PROBE 按既有 SongAvailabilityPolicy 不可播放；T3/T4 把这个状态显式传给服务端列表和 Android。
- 现有歌手目录是数据库分页加一次样本查询，且已有 song_artists/拼音/短词投影索引；在没有测量前不新增投影或裸计数。

## RED/GREEN 与验证结果
- T5 RED：新增协作重解析测试先失败，锁定的“人工歌名/人工歌手”被 韩红-望-国语-流行.mkv 解析结果覆盖；GREEN 后 CollaborativeArtistWritePathTest 7 tests 全部通过。
- 后端关键回归：124 tests，123 passed，0 failures，0 errors，1 skipped。唯一跳过为文件系统未暴露替换文件 identity 的既有假设用例。
- Android testDebugUnitTest：493 tests，0 failures，0 skipped。
- Testcontainers LargeExternalLibraryScanTest：1000 个四段式外部文件，自动 Bootstrap、首次 FFprobe、3 次无变化重扫通过。
- Testcontainers SearchLargeLibraryPerformanceTest：200,000 行 PostgreSQL 数据通过；观测单次耗时约为短中文选择性 43ms、短中文宽匹配 1135ms、拼音前缀 15ms、标题筛选 18ms、标签筛选 10ms。该结果是本机单次基线，不等同 p95；宽匹配是后续性能优化候选。
- Flyway 在纵向测试中从空库成功执行到 V45，包含 library_scan_state。

## 已排除或不采用
- 不用开启 Watch 替代首次扫描；不取消 SONG_NOT_READY 门禁；不按每首歌曲裸 +1 维护歌手/歌曲计数。
- 不把同一网络 IOException 直接归因到 mpv、NAS、IPv4/IPv6 或服务端；真实故障请求和日志仍需现场采集。
- 不把 1000 行扫描、200k PostgreSQL 搜索、mock FFprobe 或 APK 单测当作真实 NAS/TV/Android/mpv 验收。

## 下一步
- 在 Docker/Testcontainers 上继续补充/执行四首真实样本的纵向集成，断言歌手、语种、标签、拼音、ready 与播放接口的一致性。
- 对 200k 量测重复冷/热、多次查询并记录执行计划、RSS、连接数和扫描 probe/hash/UPDATE 计数；只有稳定超过确认后的阈值才改 SongRepository 或引入投影。
- 现场验证外部 NAS 挂载/权限、Android CONTROLLER/COMBINED 真机点唱、Range 流、音轨和 H5/Windows 客户端。

## 未验证与遗留
- 尚未连接用户真实 NAS、部署数据库、实际服务地址、Android 设备或 mpv；无法据此确认现场“歌手页为空”的具体请求失败码。
- 尚未执行 20 万文件真实 NAS 扫描；当前 1000 文件门禁只验证流程和无变化重扫语义。
- 200k 宽匹配一次约 1.1s，尚未形成硬件校准后的 p95 门槛，也没有在无门槛前做查询重写。
- Docker/Testcontainers 纵向门禁使用临时数据库和临时文件，源库保持只读原则。
