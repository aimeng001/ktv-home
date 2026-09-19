# TASK_STATE：曲库闭环观点审核与 TDD 实施

## 当前阶段
- 用户已确认按计划执行；T0 证据审核和整改计划已交付。
- T1–T5 原有切片保持；本轮在未提交工作树追加 T2/T3/T4 的根因修复：租约 heartbeat/fencing、根身份校验与显式 rebind、目录 revision 写入契约、Android 根目录页面刷新。
- 目标项目为 `D:\mimocode\ktv-home`；本状态快照位于 `docs/library-catalog-remediation/`，不覆盖项目根目录另一轮安卓整改的 PLAN/TASK_STATE。

## 本轮已实施
- 新增 `LibraryIdentity` 和 V46：保存规范化根目录加文件系统证据；路径不同即 MISMATCH，同路径证据不可验证才 UNKNOWN；V45 旧行回填路径值，保留升级路径。
- `LibraryScanStateStore` 的 claim/heartbeat/完成/失败均按 owner、scanId、generation fencing；PARTIAL/FAILED 不再覆盖 `last_successful_scan_at`，并保留 `errorCode/errorMessage`；新增管理员确认的 `/api/admin/scan/rebind`。
- `LibraryScanLease` 定时心跳和批事务前后 ownership check 接入 `LibraryScanCoordinator`；失效任务不标记完成、不发送成功回调；目录遍历不再吞掉 `LeaseLostException`。
- 新增 `CatalogRevisionService` 和 V46 表；扫描结束有已提交 DB 变化才批量递增，人工编辑、重解析、音乐元数据、AI 分类、管理员音频语义修改同步递增。移除扫描热路径中逐首 projection 递增。
- 公共状态增加 `rootIdentityState/countsKnown`；Android 兼容旧响应，并在 catalog revision 变化时刷新排名、新歌、语种、标签及筛选页。
- 保留外部曲库只读约束；rebind 只改数据库状态，不改 NAS 文件。

## RED/GREEN 证据
- 根身份 RED：不同临时根在 Windows 无稳定 `fileKey` 时曾错误返回 UNKNOWN；GREEN：路径前缀比较后返回 MISMATCH，并补迁移回填。
- 租约 RED：心跳返回 0 后旧 worker 仍可继续收尾/回调；GREEN：定时心跳、批事务 ownership check、完成更新行数判断和 LeaseLostException 传播。
- 状态 RED：PARTIAL/FAILED 收尾会清空错误证据；GREEN：保留错误码/消息并只在 COMPLETED 更新成功时间。
- revision RED：扫描投影逐首 bump；GREEN：扫描尾部单次 bump，管理写路径按事务 bump。

## 验证结果
- 后端目标回归：10 tests，0 failures，0 errors。
- 后端全量：`mvnw.cmd -o -Dmaven.repo.local=D:\mimocode\ktv-home\.maven-local test`，726 tests，0 failures，0 errors，4 skipped；Docker/Testcontainers 可用。
- Flyway 完整性和升级路径从空库/V24 均执行到 V46；V46 `root_identity` 回填及 `library_catalog_revision` 建表已通过。
- Android：`gradlew.bat testDebugUnitTest --offline` 通过；`gradlew.bat assembleDebug --offline` 通过。
- `git diff --check` 已清理末尾空行；未 push、未部署、未触碰真实 NAS。

## 未验证与遗留
- 尚未连接用户真实 NAS、部署数据库、实际服务地址、Android TV/遥控器或 mpv；现场“歌手页为空”的请求码、挂载身份和播放链仍需人工验收。
- 尚未执行 20 万文件真实 NAS 扫描；当前 1000 文件门禁只覆盖流程与无变化重扫语义。
- 状态接口仍同步执行多个摘要 COUNT；T6 只对搜索量测，尚未有状态轮询基准，暂不声称性能问题已解决。
- 200k 宽匹配本机单次约 1.1s，未形成硬件校准 p95 门槛；不得在没有新证据前改 SongRepository 或引入投影。
- 非 Spring 直接 `LibraryScanService.startScan()/scanAll()` fallback 保持兼容；生产启动、管理员扫描、Watch 入口由 `LibraryScanCoordinator` 接管，fallback 仍需专项调用链验收。
