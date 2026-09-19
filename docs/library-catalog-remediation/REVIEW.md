# 曲库闭环整改：ChatGPT 观点审核与证据

日期：2026-09-19  
基线：D:\mimocode\ktv-home，master，a252936（v1.0.23）。审核开始时 git status --short / git diff --stat 均无输出。  
审核对象：根目录 chatgpt审查1.md。适用规则：根目录 AGENTS.md；本次检索未发现更深层 AGENTS.md。  
交付范围：观点审核、TDD 实施计划、核心代码草案；不修改业务源码，不修改部署，不接触真实曲库。  
阅读顺序：本文件 → PLAN.md → CORE_CHANGES.md → TASK_STATE.md。

## 1. 结论

主方向正确：复用已有“歌曲文件 → PostgreSQL → 查询 API → Android 点歌”链路，补齐启动建库、状态解释和页面刷新。不要重做一套歌曲库，也不要先用头像和 AI 填充页面。

但不能照原文直接实施，至少应纠正：

1. “无法连接点歌服务”证明某次网络请求失败，不证明具体根因，更不能据此断言后端数据库为空。本次没有拿到原截图、部署日志或真机网络现场。
2. Fast Index 已实现歌曲和歌手关联入库，但未探测完成的记录不得入队或串流。应做到“先能浏览，准备完成后才能点唱”。
3. 公共曲库状态 API 确实不足；管理员扫描进度已经很完整，应该复用、脱敏和持久化必要状态，而非重新发明扫描系统。
4. 当前探测阶段的元数据优先级会覆盖文件名结果。若以用户规范命名为权威来源，必须统一两阶段和重解析的数据规则。
5. “忘不掉的你”首字母是 wbddn，原文 wbdndn 错误。已调用当前 PinyinUtil 验证。
6. SQL 聚合确实存在，但没有实测证据证明 20 万行必然不可用；现有 V37/V40 已有搜索投影和索引。先测量，再决定是否新增歌手目录投影。
7. 不能扫描一首就无条件 song_count + 1；重扫、重复文件、多歌手、改名、删除、状态变化均会导致计数漂移。
8. MANAGED 是项目支持的正常模式；不能仅凭 source-music 有很多文件就认定配置错误并拒绝运行。

## 2. 逐项裁决

| 原观点 | 裁决 | 依据与实施处理 |
|---|---|---|
| 四段式解析已存在 | 接受，已运行验证 | 两个用户样例均 RECOGNIZED；复用 FilenameParser，不重写 |
| 安卓客户端“根本没有正常连到服务” | 证据不足，收窄结论 | KtvTransport 将 IOException 统一映射为 NETWORK_ERROR；现场定位 DNS/路由/地址/端口/超时/服务状态，不能直接改网络代码 |
| 没有首次启动自动建库 | 代码层面成立 | startScan 调用方为管理员入口；Watch 默认关闭，开启后仅注册事件；启动 ArtistProfileBootstrap 只补已有数据库数据 |
| 新增 Bootstrap | 接受，范围限定 | 首先仅 EXTERNAL_READ_ONLY；复用同一个扫描执行器及互斥；手动、监听、启动共用调度 |
| Fast Index 后马上点唱 | 拒绝 | 违反 SongAvailabilityPolicy，可能让未探测/失效媒体进入播放 |
| 新建扫描进度 | 部分接受 | 现有 ScanProgress + /api/admin/scan/progress 可复用；补持久化、公共 DTO 和重启恢复 |
| 拼音功能尚未接数据库 | 不够准确 | 搜索仓储、拼音写入、Android SongApi 均存在；用纵向测试定位实际缺口 |
| 流行需要立即迁移为 genre | 延后 | 现在 tags 可完成目标；正式风格字段具有价值，但涉及存量来源和所有调用方，不是歌手页空白的已证实根因 |
| 歌手目录必须预计算 | 待性能证据 | 原生查询确实聚合；先测首屏/翻页/字母/性别统计，再作决定 |
| 不可从名字猜男女 | 接受 | 保留未知；全部页不能依赖性别、头像或 profile 齐全 |
| NAS 只读 | 接受 | 继续遵守 EXTERNAL_READ_ONLY 和只读挂载 |
| 4 首歌全链路验收 | 接受并修订 | 首字母改 wbddn；分索引/就绪两阶段；使用真实可播放 MKV，最终检查队列和媒体读取 |
| 目录监听/恢复属于本轮必须全部重做 | 不接受扩大范围 | 启动恢复属于本轮；WatchService 自动自愈、全新定时调度、AI、多音字字典列后续需求 |

## 3. 关键证据与影响

下列路径均相对于 D:\mimocode\ktv-home。行号基于上述基线；实施前检查 HEAD 和 diff。

### E01：四段式解析和拼音已经存在

- backend/src/main/java/com/homektv/library/FilenameParser.java:118：识别语种/分类后缀。
- 同文件 :195：两个身份字段按 artist/title 写入 ParsedMeta。
- backend/src/test/java/com/homektv/library/FilenameParserTest.java:16：已有标准格式测试。
- backend/src/main/java/com/homektv/library/LibraryScanService.java:1165：创建 provisional Song。
- 同文件 :1173：写 title_py/title_init/artist_py/artist_init；:1188 写 tags；:1200 写歌手关联。
- 本轮 JShell 调用当前编译类，两首歌的歌名、歌手、国语、流行断言均 true。
- 实测：忘不掉的你 → wangbudiaodeni / wbddn；张潼瑶 → zhangtongyao / zty。

结论：不是“完全没有建库能力”。应补启动入口和稳定的数据规则。

### E02：启动扫描缺口

- backend/src/main/java/com/homektv/library/SettingService.java:38：library_watch_enabled 默认 false。
- backend/src/main/java/com/homektv/library/LibraryWatchService.java:69：start 调 reloadFromSettings；关闭则返回。
- 同文件 :84：开启时注册监听，不执行已有文件全量扫描；:173 在事件回调中调用 scanAll。
- backend/src/main/java/com/homektv/library/LibraryScanService.java:916：异步 startScan 已存在。
- backend/src/main/java/com/homektv/web/AdminScanController.java:75、:92：外部只读模式手动调用 startScan。
- backend/src/main/java/com/homektv/library/ArtistProfileBootstrap.java:39、:80：应用就绪时补歌手档案，只读取已有歌曲，并非扫描磁盘。

复现设计：空 PostgreSQL + 预先存在的两个文件 + watch 默认关闭 → 仅启动服务 → 等待稳定后查 songs 和 API。当前源码没有触发该扫描的启动入口；此集成复现尚未执行，不能描述成现场已复现。

### E03：扫描进度并非完全没有

- backend/src/main/java/com/homektv/library/LibraryScanService.java:163：IDLE/RUNNING/COMPLETED/PARTIAL/FAILED。
- 同文件 :179：ScanProgress 含阶段、发现/索引/探测计数、时间、失败路径及错误码。
- 同文件 :945：getScanProgress；目前在进程内维护。
- backend/src/main/java/com/homektv/web/AdminScanController.java：/scan/progress 已公开给管理员。
- backend/src/main/java/com/homektv/web/LibraryStatusController.java:20：公共接口只有 songs.count()。
- android-tv/app/src/main/java/com/homektv/tv/net/MediaApi.kt:132：待机页读取 totalSongs。

问题根因：公共 UI 得不到能解释“空白”的库状态，且无法从进程内进度可靠恢复上次扫描状态。不要把总记录数当作可播放数。

### E04：索引、探测和可播放是三个不同事实

- LibraryScanService.java:1179、:1214：Fast Index 媒体类型为 PENDING_PROBE。
- 同文件 :399–406：先完成枚举/索引，再处理待探测文件；探测并发 2。
- backend/src/main/java/com/homektv/library/SongAvailabilityPolicy.java:33、:75：status=ok 且存在 valid、非 pending、有具体媒体类型的文件。
- backend/src/main/java/com/homektv/repo/SongFileRepository.java:31：存在就绪文件的仓储判定；同文件提供批量查询。
- backend/src/main/java/com/homektv/web/dto/SongDto.java:11：公共列表 DTO 未提供歌曲级 playable。
- android-tv/app/src/main/java/com/homektv/tv/net/Models.kt 中 ready 是文件详情字段，不能当歌曲列表已具备就绪状态。

现有服务端拦截是正确业务规则，不可为了“能点歌”删掉。缺口在列表解释和控件状态。
阶段一结束即可浏览；阶段二逐首就绪即可逐首点唱，无须等全部探测完成。

### E05：原建议遗漏了文件名权威性冲突

- LibraryScanService.java:1517–1541：title/artist 优先 audio_tag → container_tag → lrc_tag → filename。
- 同文件 :1618–1623：language 有文件名回退；第四段写 tags。
- 同文件 :1814 附近：已存在元数据更新有锁保护，不能破坏。
- backend/src/main/java/com/homektv/library/SongReparseService.java:78–88：重解析更新指纹，并锁定 title/artist，已有人工修订路径。

复现设计：韩红-望-国语-流行.mkv，容器 title=测试文件、artist=上传者。Fast Index 是韩红/望；探测后按当前优先级可变为上传者/测试文件。此为源码确认的优先级冲突，尚未对用户媒体现场验证。

ROOT-CAUSE FIX：增加明确的结构化文件名优先策略，人工字段锁优先于自动解析；Fast Index、媒体探测、重解析/修复共用规则。不通过伪造人工锁来解决。

### E06：安卓调用是真实 API，不是全部占位

- android-tv/app/src/main/java/com/homektv/tv/net/SongApi.kt:13、:27、:45、:51：搜索、歌手分页、语种、标签、歌曲分页均访问真实后端。
- android-tv/app/src/main/java/com/homektv/tv/controller/KioskCatalogActionRouter.kt：统一路由至 ControllerCatalogActions。
- android-tv/app/src/main/java/com/homektv/tv/controller/ControllerViewModel.kt:226、:422：歌手分页和筛选。
- android-tv/app/src/main/java/com/homektv/tv/ui/KtvKioskOverlayController.kt:377：反馈主要为 Toast。
- 同文件 :479、:571：非空结果令 artistsLoaded=true，此后切回歌手页可能不重新加载；未与曲库版本绑定。
- 同文件 :488、:494：部分展示还读全局 error，需在本次点歌范围内使用对应 domainErrors。
- ControllerState.kt:47：默认 artistGender=""，已经是“全部”，无需重复修默认筛选。
- KtvKeyboardInputSession.kt:25：当前九宫格为连续点击选字母，传入搜索的是字母；不是已经实现数字串 T9 后端匹配。无需另建 T9 数据库。

结论：补持续状态、刷新触发和域隔离测试。不能用本次静态分析宣称真机点歌已成功。

### E07：网络现象和根因必须分开

- android-tv/app/src/main/java/com/homektv/tv/net/KtvTransport.kt:86：IOException → NETWORK_ERROR。
- 同文件 :123：图片字节请求也有相同提示。因此只看到一条 Toast 还需确认失败请求是否为歌手列表、头像还是其他请求。
- AppConfig.kt:259：根据保存的 serverHost 构建 http://host/api。

现场证据至少包括：实际选中的服务地址和端口、失败请求路径/时间/HTTP 或 IOException 分类、同设备 API 连通性、服务端同时间段访问日志及部署模式。不记录 token、密码或完整凭据。
本轮未拿到这些资料，不预先把改地址/加重试当 ROOT-CAUSE FIX。

### E08：歌手目录无需先补完档案才能出现

- backend/src/main/java/com/homektv/repo/SongRepository.java:184–262：song_artists 与 legacy songs UNION ALL 后按 artist_key 聚合，COUNT(DISTINCT song_id)，LEFT JOIN artist_profiles。
- CategoryBrowseService.java:68：按 status=ok 查询，默认 gender=""。
- 缺 profile 或未知 gender 不会阻塞“全部”目录。
- 歌手页为空不能简单归因于“没有 artist_profiles”。

### E09：性能风险存在，但不是已经证实的性能故障

- 上述分页与 countQuery 存在重复聚合计算；首字母目录另有查询。
- backend/src/main/resources/db/migration/V40__search_projection_hot_path.sql：已有 lower(pinyin/initials) 前缀索引和搜索投影更新。
- backend/src/test/java/com/homektv/library/SearchLargeLibraryPerformanceTest.java:67：已有至少 200,000 行搜索场景。
- LargeExternalLibraryScanTest 使用 PostgreSQL Testcontainers，但媒体边界是 deterministic fakes。

先补“真实分布的歌手目录”性能测试，记录 EXPLAIN (ANALYZE, BUFFERS) 与延迟。现有 20 万搜索测试不能证明歌手列表性能，合成扫描也不能证明 NAS 真实 FFprobe 吞吐。

### E10：部署保持两种模式

- docker-compose.nas.yml：EXTERNAL_READ_ONLY；source-music read_only:true、create_host_path:false。
- docker-compose.yml:46：MANAGED 默认值。
- LibraryModePolicy.java:24：按模式选择 sourceLibraryPath 或 ktvLibraryPath。

不把普通 Compose 改成一律只读；自动建库第一阶段限定外部曲库。目录“存在且可读”不能证明 NAS 挂载身份正确，也不能证明一个空目录是真空库而非挂载丢失。

## 4. 本轮验证与限制

本轮执行现有测试，未新增业务测试或修复业务代码：

| 测试类 | tests | failures/errors | skipped |
|---|---:|---:|---:|
| FilenameParserTest | 16 | 0/0 | 0 |
| SongAvailabilityPolicyTest | 4 | 0/0 | 0 |
| CategoryBrowseServiceTest | 14 | 0/0 | 0 |
| SongSearchServiceTest | 10 | 0/0 | 0 |
| LibraryWatchServiceTest | 2 | 0/0 | 0 |
| LibraryStatusControllerTest | 1 | 0/0 | 0 |
| IncrementalLibraryScanTest | 46 | 0/0 | 1 |
| ExternalLibraryPermissionScanTest | 2 | 0/0 | 0 |
| CollaborativeArtistWritePathTest | 6 | 0/0 | 0 |

合计 101 个用例：100 通过、1 跳过、0 失败、0 错误。跳过用例为 replacementWithSamePathSizeAndMtimeReprobesWhenFileIdentityChanges；原因是文件系统未暴露替换文件的新 identity，触发既有 Assumption；不能计入已覆盖。
这些是现有行为基线，不是新整改通过证明。新功能测试仍处于计划状态。
本轮未运行完整回归、Android 构建/真机、PostgreSQL 纵向集成、20 万性能测试、真实 NAS、播放验收。

## 5. 实施范围建议

必须完成：T0 现场证据与契约 → T1 文件名规则一致 → T2 启动建库及恢复 → T3 状态与就绪 DTO → T4 安卓展示与刷新 → T5 存量修复及纵向闭环。
性能阶段 T6 必须测量；只有不达目标时才增加投影。正式 genre、离线歌手资料包、多音字字典另行规划。
详见 PLAN.md；关键实现和失败测试草案见 CORE_CHANGES.md。

