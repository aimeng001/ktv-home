# Home KTV v1.0.55 发布说明

Home KTV v1.0.55 聚焦于多端并发状态机一致性、7×24h 长期运行稳定性、极端流媒体容错与安全防护，修复了物理 TV 在线时的音效死循环广播风暴、手机遥控端实时转码抢占槽位、长事务霸占连接池及调度线程饿死等深层缺陷。

---

## 🚀 核心改进与问题修复

### 1. 多端并发与状态机一致性（Multi-Client Concurrency）
- **阻断无限递归广播风暴**：修复物理 TV 在线时触发音效导致所有 H5 客户端陷入死循环 API 请求与 WS 广播的风暴漏洞；对齐音效 ID 映射字典；发声由 WS 广播统一驱动并严格绑定非静音主播放端。
- **静音遥控端与 FFmpeg 转码槽位隔离**：引入 `isSilentConsole` 严格区分主播放端与静音遥控台；多台手机扫码遥控时对 `liveTranscode` 流自动降级为封面与实时歌词同步，100% 保护服务端 4 并发 FFmpeg 实时转码预算留给大屏。
- **进度上报版本单调性防护**：前端 `reportProgress` 携带 `seek_sequence`，服务端拦截并丢弃滞后的旧在途进度帧，彻底解决切歌或拖动进度条被主屏旧包回跳的问题。
- **移动端锁屏即时唤醒**：WebSocket 客户端监听 `visibilitychange` 与 `online` 事件，切回前台立即唤醒重连与心跳探测。

### 2. 7×24h 长期稳定性与数据库连接保护（Soak & Leak Resilience）
- **Spring 定时调度线程池化**：配置 `poolSize = 4` 的多线程任务调度器与未捕获异常日志保护，头像异步分页入队，杜绝资产清理等重型任务饿死 500ms 播放压力探针与 10s 看门狗。
- **剥离写事务内网络与文件 I/O**：重构 `MusicMetadataApplyService`，将外部平台 HTTP 详情查询、5s 限流等待及封面图片下载/重编码彻底移出数据库写事务，事务耗时从秒级骤降至毫秒级，根除 HikariCP 连接池耗尽风险。
- **HTTP 响应体读取超时中断保护**：为 `MusicSourceHttp` 与 `ExternalCoverService` 的 `HttpClient` 配置 5s 连接超时，并为 `InputStream` 响应体读取增加虚拟线程超时中断，防止网络半开连接永久死锁 `ProviderCallGuard`。
- **重解析歌手字典 $O(1)$ 索引优化**：引入 `PreindexedKnownArtists`，批量重解析 4.7 万歌手字典仅预构建一次，消除事务内千万次重复循环与散列开销。

### 3. 极端流媒体与安全加固（Chaos & Security Hardening）
- **非标视频流流映射防封面流误判**：`LiveTranscodeCommandBuilder` 使用 `-map 0:V:0?`，排除带 `attached_pic` 静态封面的 MKV/MP4 误将封面图当视频输出的问题。
- **锁外流媒体探测**：`StreamController.shouldCopyAudio()` 将 `ffprobeService.probe()` 移出 `audioCodecCache` 同步监视器锁，防止慢盘卡死全局流媒体请求。
- **SSRF 范围补齐**：公网头像/封面图源校验复用统一私网判定，补齐 IPv6 ULA（`fc00::/7`）与 CGNAT（`100.64.0.0/10`）等内网地址拦截。
- **CSV 公式注入防护**：心愿单导出 CSV 时对以 `=`, `+`, `-`, `@`, `\t`, `\r` 开头的字符串自动前置单引号转义。
- **历史记录重唱探测兜底**：`HistoryController.repeat()` 补充 `songProbeService.ensurePlayable()` 校验，确保曲目准备就绪。

### 4. 网页端交互死角打磨
- **软键盘布局与输入法兼容**：修复 `.kb-mode-tabs` 3 列网格布局换行问题；补充中文 IME 拼音输入合成态（`isComposing` / `keyCode 229`）防误拦截。
- **语种与标签目录门禁**：`KtvThreeColumnBrowser.vue` 中的 `ensureLanguagesLoaded` 与 `ensureTagsLoaded` 接入 `reqGate` 竞态门禁，杜绝快速切换 Tab 时的 loading 状态冲突。

---

## 📦 验证记录
- **后端测试**：全量单元与集成测试套件通过（184+ 核心测试全部通过）。
- **前端测试**：90 个测试文件、721 个测试用例 100% 通过。
- **构建输出**：前端产物重新编译打包并写入 `backend/src/main/resources/static/m/`。
