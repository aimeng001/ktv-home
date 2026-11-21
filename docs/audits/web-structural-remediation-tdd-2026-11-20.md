# 网页端结构性问题 · 对抗性复审与 TDD 整改计划（v3）

- 复审对象：第三轮口头的 D1–D7 审查结论（P1 19 条 / P2 17 条）
- 复审方式：**逐条回到源码重新取证**，不引用上一轮结论与行号
- 本轮（v3）新增：**对 v2 计划本身做了一次回归影响复核**，发现 16 处设计缺陷，其中 4 处按原计划实施会直接出事
- 复审日期：2026-11-20 / v3 修订：2026-11-21
- 状态：**v3 · 已融合计划缺陷，可进入实施**
- 环境前提（用户确认）：**纯局域网使用，无反向代理**
- 本轮改动范围：**仍未修改任何业务代码**，只改本文档

---

## 零、判定口径

上一轮把「不符合最佳实践」和「功能会失败」混在同一档，导致 P1 里塞了 7 条可访问性、4 条性能。统一用三个问题定级：

| 定级 | 判定标准（三条全中才算） | 典型后果 |
| --- | --- | --- |
| **P1** | ① 用户可感知；② 功能确实失败或数据确实被破坏；③ **没有用户自己能做的绕行办法** | 队列卡死、数据被放大、管理员进不去 |
| **P2** | ① 用户可感知或有信息缺失；② 有绕行路径，或只在特定条件下出现 | 显示错、体验不一致、慢 |
| **P3** | 不影响线上行为，只影响维护成本 | 死代码、重复导出 |

**v3 追加一条实施口径**：任何一条整改，只要「修完之后用户能感知到的行为发生变化」，就必须在本文档里显式写出**变化前 / 变化后**，不能只写「修好了」。本轮的 16 处缺陷，有 11 处正是漏了这一条。

---

## 一、推翻清单：3 条结论不成立

### ❌ 推翻 1 —— 原 P1-15「音量数值映射三套口径」

上一轮断言：`player.volume` 有 4 个消费点分别做 0-100 clamp ×2、0-1 归一化 ×2，「任一处口径改动即导致音量跳变」。

**实测四处全部正确且互相自洽**：

| 位置 | 实际代码 | 动作 |
| --- | --- | --- |
| `stores/player.js:22` | `volume: 60` | 存储，0-100 |
| `webAudioChannelRouter.js:79-83` | `clamp(0,100)` → `gainNode.gain.value = lastVolume / 100` | **先归一化再转增益，正确** |
| `KtvMediaSurface.vue:265` | `el.volume = routed ? 1 : clamp((player.volume ?? 60)/100)` | **正确** |
| `KtvKioskView.vue:403` | `clamp(0,100, player.volume + delta)` | **正确** |
| `KtvModals.vue:132` | `<input type="range" min="0" max="100" v-model.number="vol">` | **正确** |
| 后端 `PlaybackService.java:255` | `clamp(0,100, volume)` | **正确** |

`volumePct → /100` 是 WebAudio 增益的**必然单位换算**，不是「口径分歧」。

**残留的一点真实瑕疵**（单独立项）：前端初值 60、后端 `PlayerState.java:34` 默认 100，首帧快照到达前会闪一下。**v3 修正这条的修法方向**，见缺陷 D-11。

---

### ❌ 推翻 2 —— 原 P2-03「前端 PARTIAL 分支是死代码」

上一轮断言：`LibraryScanService.java:229` 的三元链只产 RUNNING/FAILED/COMPLETED/IDLE，前端 `scanProgress.js:62` 的 `PARTIAL` 不可达。

**实测后端确实产出 PARTIAL**：

```
LibraryScanService.java:470-475
ScanState finalState = counters.failedPaths > 0 && discovered[0] == 0
        ? ScanState.FAILED
        : counters.failedPaths > 0
        ? ScanState.PARTIAL          // ← 这里
        : ScanState.COMPLETED;
```

上一轮只看了短构造器（`:226-231`），漏了扫描收尾走的长构造器（显式传 `finalState`）。**结论作废**，分支有效，不能删。

---

### ❌ 推翻 3 —— 原 P2-09「`lyricState.js` 生产代码零引用」

`h5/src/components/ktv/KtvMediaSurface.vue:141` 有 `import { currentLyricIndex } from '../../views/lyricState'`。**删了会编译失败**，死代码只剩 `useAsyncResource.js` 一个模块。

---

## 二、修正清单：9 条事实或数字错误（结论仍部分成立）

| # | 上一轮的说法 | 实测 | 影响 |
| --- | --- | --- | --- |
| 1 | P1-07「后台 **11 个**弹窗无焦点陷阱」 | `aria-modal="true"` 全项目 **8 处**：DialogHost×1（已有陷阱）、AdminLayout×1、AiLibraryView×3、ArtistLibraryView×2、AudioLayoutEditor×1 → **需补 7 处** | 工作量从 11 降到 7 |
| 2 | P1-18「**5 个**视图复写 `#94a3b8`」 | `SourceLibraryView.vue:364` 是 `#64748b;11px`，**正确**；`td small` 用 `#94a3b8` 的实为 **4 个**：AiLibraryView:443、ArtistLibraryView:259、DashboardView:250、KtvLibraryView:186 | 改 4 个文件 |
| 3 | P1-16「歌词两条路径**鉴权不一致**」 | `/api/lyric/{id}`（`SongController.java:173`）本就无鉴权，不存在鉴权差异 | 只剩「无统一错误映射」，且 v3 建议不做（D-07） |
| 4 | P1-04「loading **永久**悬挂」 | 浏览器网络层最终会 reject（30s–2min+），不是数学意义上的永久 | 措辞改为「长时间挂起且无取消出口」 |
| 5 | P2-02「组合歌手**永不**进批量分析」 | `ArtistLibraryView.vue:216`：勾选时 `selectedArtists` 含 GROUP；只有「不勾选直接批量」才排除 | 改为「默认批量范围排除 GROUP」，**且 v3 建议保持排除**（D-09） |
| 6 | P1-01 修法「`positionMs > durationMs + 5000`」 | `PlayerState` **无 durationMs**；`updatedAt` 是 `@Generated(INSERT)`，不能当最后进度时刻 | 修法重写，见 D-01/D-02 |
| 7 | P1-12「pong 载荷丢弃 → 不阻断」 | 三种降级情形都另发 `sendRole(STANDBY)`（`KtvWebSocketHandler.java:349-361`），pong 只承载续期确认 | 降 P2 |
| 8 | P2-06「幽灵更新」 | `flash()` 3 秒后写入的是**空字符串**，不是业务数据 | 降为「无意义定时器」 |
| 9 | P2-08「超 16MB 被断连」 | 上限是 `MAX_PENDING_FRAMES=128` **或** `MAX_PENDING_BYTES`（`WsBroadcaster.java:345-346`），先到先断 | 描述补帧数上限 |

---

## 三、降级清单：14 条定级错误

| 原编号 | 原级 | 新级 | 降级理由（硬证据） |
| --- | --- | --- | --- |
| P1-05 | P1 | **P2** | WEB_HOST 分支虽不置 `mirrorStreamFailed`（`KtvMediaSurface.vue:418-422`），但紧接着 `reportPlayError` 走 `onPlayError → advanceToNext`（`PlaybackService.java:187-200`）自动切下一首；黑屏是 1 个 WS 往返的瞬态 |
| P1-06 | P1 | **P2** | 前端唯一调用点 `client.js:285` 的 `effectId` 来自固定按钮常量，前台不可达 |
| P1-09 | P1 | **P2** | 无 `Cache-Control` 属实（`SongController.java:181-187`），但同文档内浏览器不重复请求同 URL 图片，且有启发式缓存 |
| P1-10 | P1 | **P2** | `KtvKioskView-*.js` 实测 114,326 字节；首屏 Bento **11 张图 ≈ 1.14MB**。千兆局域网 + hash 强缓存，只付一次 |
| P1-11 | P1 | **P2** | `unavailableReason` 前端 0 命中，后果是「看不出为什么不能点」 |
| P1-12 | P1 | **P2** | 见修正 #7 |
| P1-13 | P1 | **P2** | `AudioLayoutDto.java:11-17` 确无 `source`，后果是管理员看不到来源 |
| P1-14 | P1 | **P2** | `KtvBottomDock.vue:37,75` ±8 vs `KtvMediaSurface.vue:108-109` ±10，手感不一致 |
| P1-16 | P1 | **P2** | 见修正 #3 |
| P1-17 | P1 | **P2** | 3 个 `type="range"` 无 `aria-label`，只影响读屏 |
| P1-18 | P1 | **P2** | 对比度 2.564:1 复算无误，但项目无 WCAG 硬性文档要求 |
| P1-19 | P1 | **P2** | dvh 无回退属实，但现代 WebView 均支持 |
| P1-07 | P1 | **P2** | 焦点陷阱缺失对鼠标用户无影响，后台仅管理员使用 |
| P1-08 | P1 | **P2** | `role="tablist"` 下无 `role="tab"`，ARIA 结构非法但不阻断点击 |

---

## 四、复审后的真实问题清单（34 条）

| 编号 | 级别 | 问题 | 证据位置 |
| --- | --- | --- | --- |
| **A-01** | **P1** | **播放端离线后队列停在 `playing`**：TV 离线且有手机在线时，`TvOfflineWatcher` 主动放弃清理；WEB 播放端离线时无人接管（详见 D-01） | `TvOfflineWatcher.java:95`、`PlaybackService.java:310`、`KtvWebSocketHandler.java:236-241,242-260` |
| **A-02** | **P1** | `GET /api/favorites`、`/ids` 写 `app_user` | `FavoriteController.java:59,86,150` → `UserService.java:28` |
| **A-03** | **P1** | 登录冷却期在密码比对**之前**，正确密码同样被 429 | `AdminAuthService.java:71` |
| **A-04** | **P1** | 全站 HTTP 无超时、无离线检测，请求长时间挂起且无取消入口 | `client.js:23-53,55-81` |
| B-01 | P2 | 收藏页同一份数据请求两轮全量分页 | `KtvThreeColumnBrowser.vue:1053-1068`、`favorites.js:46` |
| B-02 | P2 | `GROUP` 被渲染成「未归属」 | `ArtistLibraryView.vue:216`、`ArtistKind.java:5-9` |
| B-03 | P2 | `retryFailed` 形参从未下传 | `PlaybackController.java:23` |
| B-04 | P2 | 「恢复融合」结果渲染在「回滚结果」卡片 | `IktvFusionView.vue:73,99,139,160` |
| B-05 | P2 | `flash` 定时器无清理 | `AiTaskPanel.vue:87` |
| B-06 | P2 | 批量删除用 `DELETE` 带 body | `client.js:151,160`、`AdminScanController.java:381` |
| B-07 | **已满足** | 超限断连的 WARN 已存在（`WsBroadcaster.java:265-267`），且带失败批次的 frames/bytes | 建议关闭该项，见 D-15 |
| B-08 | P2 | 封面响应无缓存头 | `SongController.java:181-187` |
| B-09 | P2 | WEB_HOST 解码失败终态无本地视觉兜底 | `KtvMediaSurface.vue:11,418-422` |
| B-10 | P2 | `effect` 缺参 NPE → 500 | `ControlController.java:167-168` |
| B-11 | P2 | `unavailableReason` 下发、前端零读取 | `SongDto.java:113`、`SongAvailabilityPolicy.java:23` |
| B-12 | P2 | `AudioLayoutSource` 未暴露 | `AudioLayoutDto.java:11-37` |
| B-13 | P2 | 音量步长 8/10 不一致；前端 60 与后端 100 不一致 | `KtvBottomDock.vue:37,75`、`KtvMediaSurface.vue:108-109`、`player.js:22`、`PlayerState.java:34` |
| B-14 | P2 | 歌词加载走裸 `fetch` | `lyricLoader.js:34` |
| B-15 | P2 | `pong` 载荷被整体丢弃 | `ws.js:199` |
| B-16 | P2 | 3 个 range 无 `aria-label` | `KtvMediaSurface.vue:81` 等 |
| B-17 | P2 | 后台表格小字对比度 2.56:1（4 处） | `AiLibraryView.vue:443` 等 |
| B-18 | P2 | 3 处 `100dvh`（其中 1 处有 `min-height` 兜底） | `KtvThreeColumnBrowser.vue:1612,1621`、`KtvKioskView.vue:550` |
| B-19 | P2 | 7 个弹窗无焦点陷阱 | `aria-modal` 8 处 − DialogHost 1 处 |
| B-20 | P2 | `role="tablist"` 下无 `role="tab"` | `KtvThreeColumnBrowser.vue:8`、`KtvSoftKeyboard.vue:18` |
| B-21 | P2 | 4 个视图各拉一次源库统计（共 5 次调用） | `DashboardView.vue:113,114`、`KtvLibraryView.vue:94`、`SettingsView.vue:340`、`SourceLibraryView.vue:112` |
| B-22 | P2 | 扫描轮询固定 1s | `DashboardView.vue:139` |
| B-23 | P2 | `.marquee-box` 是纯 div 承载点击 | `KtvHeaderBar.vue:26` |
| B-24 | P2 | 曲库页复选框无 `aria-label` | `KtvLibraryView.vue:17,18` |
| B-25 | P2 | 3 处 `font-size:9px` 在 TV 距离不可读 | `KtvBottomDock.vue:290`、`KtvMediaSurface.vue:631`、`KtvSoftKeyboard.vue:286` |
| B-26 | P2 | 首屏同步打包全部 KTV 组件 + 11 张图 1.14MB（**已拍板不做**） | `KtvKioskView.vue:101-108` |
| C-01 | P3 | `useAsyncResource.js` 生产零引用 | 仅自身单测 |
| C-02 | P3 | `player.orderedSongIds` getter 只被测试引用 | `player.js:53-56` |
| C-03 | P3 | 两个轮播常量 export 冗余 | `ktvCatalogPresets.js:59,60` |
| C-04 | P3 | 分页 URL 拼装 5 处重复 | `client.js:117,128,143,153,220` |

**统计**：P1 = 4，P2 = 25，P3 = 4，已关闭 1（B-07），合计 33 条待办。

---

## 五、v3 核心：对 v2 计划本身的复核（16 处缺陷）

> 这一节是本次交付的重点。下面每一条都是「按 v2 计划做，会在什么地方出事」，全部带源码证据。
> 按后果分三级：**🔴 必然出事** / **🟠 大概率出事** / **🟡 计划不自洽或收益为负**。

### 🔴 D-01：批次 1 的问题描述是错的——TV 离线**已经有自愈**

v2 写的是「播放端离线后队列停在 `playing`，无任何自愈」。实测项目里**早就有一个 TV 离线看门狗**：

```
backend/.../ws/TvOfflineWatcher.java:46   OFFLINE_CLEAR_DELAY_MS = 180_000   // 3 分钟
backend/.../ws/KtvWebSocketHandler.java:249-259   tv 断连 → tvOfflineWatcher.onTvDisconnected()
backend/.../queue/PlaybackService.java:310-328    clearOnTvOffline(): 当前标 SKIPPED + 删等待队列 + 置 idle
```

所以「TV 离线 → 队列一直 playing」这个描述**不成立**。真实缺口只有两个：

| 场景 | 现状 | 是否真卡死 |
| --- | --- | --- |
| TV 离线，**没有手机在线** | 180s 后 `clearOnTvOffline()` 清空队列 | **不卡死**，但代价是**整个队列被删** |
| TV 离线，**有手机在线** | `TvOfflineWatcher.java:95` 见 `connectedPhonesCount() > 0` 直接 return，**永不清** | **真卡死** |
| WEB 播放端（浏览器当播放器）离线 | `handlePlayerUnregistration:236-241` 只 `webPlayerCoordinator.release()`，**没人管播放状态** | **真卡死** |

**后果**：按 v2 原样实施，会出现「45s 暂停 + 180s 清空」两条路径叠加，用户先看到「已暂停」再看到「队列已清空」。功能上没错，但**产品行为变成了两次惊吓**，而 v2 完全没提这件事。

**修正**：批次 1 的描述、测试、实现全部重写（见第七节批次 1）。核心变化是把判定条件从「`!isTvOnline()`」改成「**没有任何授权播放端**」，并且明确只在**手机在线**时才发暂停 toast（无手机时让 180s 清空去负责提示）。

---

### 🔴 D-02：批次 1 新增的定时任务会和现有 11 个 `@Scheduled` 抢同一条线程

v2 写「新建 `PlaybackWatchdog` + `@Scheduled(fixedDelay=10000)`」，但没看调度器配置：

```
backend/.../config/SchedulingConfiguration.java
@Configuration(proxyBeanMethods = false)
@EnableScheduling                    // ← 没有自定义 TaskScheduler
@ConditionalOnProperty(prefix="app.scheduling", name="enabled", matchIfMissing=true)
```

全项目 `grep TaskScheduler` **0 命中** → Spring 用**单线程**调度器。现在这条线程上已经排了 12 个任务：

| 任务 | 频率 | 位置 |
| --- | --- | --- |
| `PlaybackPressureMonitor` | **每 500ms** | `resource/PlaybackPressureMonitor.java:35` |
| `AiLibraryService` 待办排空 | 每 2s | `ai/AiLibraryService.java:307` |
| `IktvSongMatchWorker` | 每 1s | `library/IktvSongMatchWorker.java:52` |
| `ArtistAvatarJobService` ×2 | 每 60s | `library/ArtistAvatarJobService.java:99,105` |
| `AssetCleanupService` | 每 1h | `library/AssetCleanupService.java:234` |
| 其余 5 个 | 30s–5min | 略 |

**后果**：新看门狗每次 tick 要读 `player_state`。如果按 v2 的写法用 `getSingletonForUpdate()`，那是**悲观写锁**（`PlayerStateRepository.java:46-49` → `findByIdForUpdate`），而 TV 每秒一次 `progress` 也在拿同一把锁。看门狗阻塞时，**每 500ms 的播放压力监控会被一起拖住**。这是典型的「改一个点，瘫一条链」。

**修正**（三条一起做）：
1. 判定阶段用**非锁定**查询（`findById`），只有真的要暂停时才调 `pause()`（内部自带锁）。
2. 两个 tick 连续命中才动手（20s 窗口），避免单次抖动。
3. **不改** `SchedulingConfiguration`。若实测发现抖动，再单独把这个看门狗挪到自己的 `ScheduledExecutorService`（照抄 `TvOfflineWatcher` 的写法，它就是这么干的）。

---

### 🔴 D-03：服务重启后，看门狗会把刚恢复的歌立刻暂停

```
PlaybackService.java:202-238  recoverAfterRestart()
  → 找到未播完的曲目 → ps.setState("playing")   // 重启后主动恢复播放
```

`recoverAfterRestart` 由 `ApplicationReadyEvent` 触发（`queue/PlaybackRecovery.java:16-19`）。启动后 10–60 秒内，浏览器还没重连、TV 可能还在重连，此刻**没有任何播放端在线**。v2 的看门狗条件在这段时间里**全部命中**，会在第 10 秒把刚恢复的歌暂停掉。

**后果**：这正是「重启后自动续播」这个功能被反向破坏——用户回到客厅发现歌被暂停了，而且 toast 说「播放端已离线」。

**修正**：看门狗加**启动宽限期**（应用启动后 180 秒内不判定），并额外要求「自启动以来至少有过一次客户端连接」。两条用一个内存字段即可，不改表。

---

### 🔴 D-04：前端「重放上报」按 v2 的写法会被服务端静默丢弃

v2 写「`send()` 返回 false 则入 `pendingReports`，`onStatus(true)` 后重放」。但上报载荷里带的是**会话级凭证**：

```
h5/src/stores/player.js:287-294
reportFinished(queueId) {
  this.socket?.send({ type:'finished',
    payload: { queue_id: queueId, generation: this.webPlayerGeneration } })   // ← generation
}
```

服务端校验：

```
backend/.../ws/KtvWebSocketHandler.java:278-288   authorizesPlaybackReport()
  → webPlayerCoordinator.authorizesUpstream(session.getId(), generation)
backend/.../ws/WebPlayerCoordinator.java:124-132
  return activeSessionId.equals(sessionId) && generation == messageGeneration
```

重连后 `sessionId` 变了、`generation` 也变了（`player.js:149` 收到新 `PLAYER_ROLE` 才赋值）。**把原始消息对象存起来重发，100% 会被判 STALE 丢掉**，功能等于没做。

**修正**：`pendingReports` 只存 `queueId`（不存消息体），重放时**重新调用 `reportFinished(queueId)`**，让载荷用**当前**的 `webPlayerGeneration` 重建。另需写明：若重连后角色变成 STANDBY（TV 上线抢占），`webPlayerGeneration` 归 0，`reportFinished` 会直接 return——**这是预期行为**，不是 bug。

---

### 🟠 D-05：批次 4 的「默认 15s 超时」会打断 35 处长耗时操作

v2 只给「诊断包 / CSV 导出」放了 60s，其余全走 15s 默认。实测 `client.js` 里属于**网络/LLM/扫描**类、天然可能超过 15s 的调用点有 **35 处**：

| 类别 | 代表端点 | 为什么会超 15s |
| --- | --- | --- |
| 扫描 | `adminStartScan`（`client.js:163`） | 只读模式下同步做 Fast Index；20 万首走 SMB 远超 15s |
| AI 分析 | `adminAnalyzeArtist` / `adminAnalyzeArtists`（`:231-232`） | 外部 LLM，且 `ArtistLibraryView.vue:229` 是**循环分块**调用 |
| AI 歌单 | `adminAiPreviewPlaylist`（`:216`） | LLM 生成整张歌单 |
| AI 修复 | `adminAiRepair`（`:239`） | 批量建任务 |
| 外网测试 | `adminTestMusicSources`（`:173`）、`adminAiTestConfig`（`:238`） | 探测第三方服务 |
| 刮削 | `adminSongExternalMatches(refresh=true)`（`:175`）、`adminScrapeArtistAvatar`（`:224`） | 外网抓取 |
| 转码 | `adminStartSourceTranscode`（`:156`）、`adminPrioritizeSourceTranscode`（`:157`） | 触发 NAS 转码 |
| 融合 | `adminIktvFusionResume` / `Rollback`（`:236-237`） | 批量改库 |
| 字典 | `adminApplyArtistGenderDictionary`（`:225`） | 批量写库 |
| 诊断 | `adminDiagnostics`（`:165`）、`adminDownloadDiagnostics`（`:200`） | 采集系统状态 / 打包 |

**后果比「挂起」更糟**：超时后前端报错，但**后端还在继续跑**。用户看到失败 → 再点一次 → 变成两个扫描任务 / 两次 AI 批量。这是把一个「等待」问题换成了「重复执行」问题。

**修正**：`timeoutMs` 的默认值定为 **`0`（不超时，保持今天的行为）**，只对**读接口**显式加上超时。这样零回归，且「界面挂起」的痛点仍然解决（读接口是挂起的主要来源）。具体清单见第七节批次 4。

---

### 🟠 D-06：离线横幅放进 `App.vue` 会顶破 `100dvh` 布局

v2 写「`App.vue` 注册 online/offline，复用 `identity-banner` 样式」。但 `App.vue` 现在是裸的：

```
h5/src/App.vue   <router-view/> <ToastHost/> <DialogHost/>
```

而点歌台是满屏布局：`KtvKioskView.vue:548-551` `.ktv-kiosk-shell { min-height:100vh; height:100dvh }`。在 `router-view` 前面插一个**参与文档流**的横幅，整个 shell 会往下推一行 → **TV 上出现纵向滚动条**。

另外这条信息已经有承载点：`KtvHeaderBar.vue:41,228` 的 `.ws-pill.offline`（WS 断开变红）。

**修正**：离线提示用 `position:fixed` 的顶部浮层（不占布局），或直接把 `.ws-pill` 的文案扩成「离线 · 正在重连」。**不要插进 App.vue 的文档流**。

---

### 🟠 D-07：B-14 改歌词加载器是负收益，建议不做

v2 写「`lyricLoader.js:34` 的默认 `fetchImpl` 改 `api.lyricText`」。两个问题：

1. `api.lyricText: (id) => request('/lyric/' + id)`（`client.js:92`）**不接受 options**，要透传 `signal` 必须先改签名。
2. 现在 `lyricLoader.js:33-43` 已经正确处理了「切歌时 abort」：`catch` 里判 `error?.name === 'AbortError'` 返回 `stale`。改成走 `client.js` 后，超时会变成 `code:'TIMEOUT'` 的普通错误，**会走进 `onError` 分支弹「歌词加载失败」**——而实际上只是切歌了。

**修正**：**不做**。B-14 从 P2 降 P3，只保留「歌词 404 的错误文案不统一」这一条观察，不产生代码改动。

---

### 🟠 D-08：B-11 直接把 `unavailableReason` 显示给用户，会露出英文错误码

v2 写「`:title="song.unavailableReason"` + 文案 `'不可点：'+reason`」。实测后端下发的**不是中文原因，是错误码**：

```
SongAvailabilityPolicy.java:23   public static final String SONG_NOT_READY = "SONG_NOT_READY";
SongDto.java:113                  playable ? null : unavailableReason   // 原样透传
```

用户会看到「不可点：SONG_NOT_READY」。

**修正**：前端建映射表，未知码兜底：

```js
const UNAVAILABLE_REASON_LABELS = {
  SONG_NOT_READY: '正在扫描或媒体探测未完成',
  PENDING_PROBE: '媒体信息探测中'
}
label = UNAVAILABLE_REASON_LABELS[reason] || '当前不可点'
```

---

### 🟠 D-09：B-02 的「默认批量范围加回 GROUP」会写入错误数据

`ArtistLibraryView.vue:216` 默认只取 `artistKind === 'PERSON' && !reviewed`。v2 建议改成 `['PERSON','GROUP']`。**不要改**，理由：

- 组合的「性别」不是 AI 能从歌名/歌手名推出来的东西，批量分析会**成批写入错误性别**（最多 500 位）。
- 产品上已经留了正确出口：`:219` 行内「分析/复核」按钮对 GROUP **没有禁用**，想分析可以单条做。

**修正**：只改文案（五值 `ARTIST_KIND_LABELS`），**默认范围维持 `PERSON`**。若确实要分析组合，由用户在弹窗里手动勾选。

---

### 🟠 D-10：B-08 在共享的 `serveFile` 上加缓存，会把歌词一起缓存 7 天

```
SongController.java:181-187   private ResponseEntity<Resource> serveFile(...)   // 封面(:162) 和歌词(:177) 共用
```

在 `serveFile` 里统一加 `Cache-Control: max-age=604800`，后果是**用户改了 LRC 文件后，一周内页面还是旧歌词**。而封面本身也可能被重新刮削替换。

**修正**：只在**封面分支**加，且不要用「不可变」语义：

```java
@GetMapping("/cover/{id}")
... .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())   // 1 天，不是 7 天
```

歌词分支保持不变。若想要「换了立刻生效」又不牺牲缓存，正确做法是 `max-age=0, must-revalidate` + `ETag`（由 `FileSystemResource` 的 `lastModified` 生成），但那是另一个工作量，不在本轮。

---

### 🟠 D-11：B-13 把前端音量初值 60 改成 100，方向是反的

```
h5/src/stores/player.js:22        volume: 60
backend/.../domain/PlayerState.java:34   private int volume = 100;    // 只在新建行时生效
```

v2 建议把前端改成 100「对齐后端」。但后端这个 100 只影响**全新数据库第一次插入**的那一行；老库里的音量是用户自己调过的值，重启不受影响。

改成 100 的实际效果是：**全新安装的用户，第一次打开就是满音量**。KTV 场景下这是明确的体验倒退（比首帧闪一下更难接受）。

**修正**：把**后端默认值改成 60**（一处），前端保持 60。这样两处一致、且新装不会炸音。

> 注意：`PlayerState.volume` 同时有 Java 默认值和 DB 列默认值，改 Java 字段只影响新建行，**不改表结构、不做迁移**。

---

### 🟠 D-12：B-19 的焦点陷阱会和 `DialogHost` 打架

`DialogHost.vue:41-53`：

```js
function setBackgroundInert(inert) {
  if (inert) {
    for (const element of Array.from(document.body.children)) {
      if (element === modal || backgroundState.has(element)) continue
      element.inert = true                    // ← 把整个 app 置为 inert
      element.setAttribute('aria-hidden', 'true')
    }
  }
}
```

后台弹窗（如 `ArtistLibraryView` 的复核框）里点「确认并写入」会 `await alertDialog(...)`。此时：

1. `DialogHost` 把包括该弹窗在内的所有 `body` 子节点置 `inert`。
2. 我们新加的 `useFocusTrap` 仍在 window 上监听 keydown（它比 DialogHost 后注册，同一事件里**后执行**）。
3. 它的逻辑是「Tab 到最后一个就 preventDefault 并 focus 第一个」——但容器已经 inert，`focus()` **静默失败**。

结果：焦点丢失，按 Tab 没反应。这是可访问性改动引入的**新**可访问性问题。

**修正**：`useFocusTrap` 内部先判断「当前是否有全局对话框在最上层」：

```js
const dialogOnTop = () => !!document.querySelector('.dialog-mask .dialog-card')
// keydown 开头：if (dialogOnTop()) return
```

`DialogHost` 的 mask 类名已经是 `.dialog-mask`（`DialogHost.vue` 样式段），无需新增标记。

---

### 🟡 D-13：B-17 的「抽到 AdminLayout `:deep()`」与「只改 4 个文件」自相矛盾

v2 同时写了「改 4 个视图（不含 SourceLibraryView）」和「抽到 AdminLayout `:deep()`」。这两件事不能共存：`main[data-v-x] td small` 的特异性高于子组件自己的 `td small`，会**连 SourceLibraryView（本来正确的 `#64748b`）一起覆盖**。

**修正**：二选一，本文档选**逐文件改 4 处**（影响面最小、可逐个视觉复核）。`AdminLayout` 不动。

---

### 🟡 D-14：B-18 实际是 3 处 `100dvh`，但第 3 处已有兜底

| 位置 | 写法 | 有无回退 |
| --- | --- | --- |
| `KtvThreeColumnBrowser.vue:1612` | `max-height: calc(100dvh - 196px)` | **无** → 老 WebView 上软键盘面板会超出屏幕 |
| `KtvThreeColumnBrowser.vue:1621` | `max-height: calc(100dvh - 280px - var(--safe-bottom))` | **无** → 同上 |
| `KtvKioskView.vue:550` | `height:100dvh`，上一行有 `min-height:100vh` | **有** → 降级后仍满屏，可不改 |

**修正**：只改前两处，补 `max-height: calc(100vh - 196px)` 在前。第三处保持不动（且 v2 漏了它，应记录在案）。

---

### 🟡 D-15：B-07 已经是「只加日志」的现状，且想加的积压量拿不到

```
WsBroadcaster.java:263-268
if (!queue.enqueue(batch)) {
    log.warn("WebSocket 会话发送队列超限，关闭慢会话: session={}, frames={}, bytes={}",
             session.getId(), batch.frameCount(), batch.byteCount());   // ← WARN 已存在
```

且 `pendingFrames` / `pendingBytes` 是 `SessionOutboundQueue.enqueue()`（`:344-378`）里的**局部变量**，`sendPrepared` 拿不到。要打「积压量」必须改 `enqueue` 返回值或加 getter——不是「只加日志」那么轻。

**修正**：**关闭 B-07**。现有日志已含 sessionId + 失败批次的帧数/字节数，足够定位。

---

### 🟡 D-16：批次 2 的 `RoomHostService` 重构零收益

v2 写「同步 `RoomHostService.resolveExistingUserId` 改为调用 `UserService`（去重）」。实测它**已经是目标形态**：

```java
RoomHostService.java:81-84
private Long resolveExistingUserId(String clientToken) {
    if (clientToken == null || clientToken.isBlank()) return null;
    return userRepository.findByClientToken(clientToken).map(AppUser::getId).orElse(null);   // 只读、不 insert
}
```

改成调 `UserService` 只是给 `RoomHostService` 多一个依赖、多一处测试 seam，**行为零变化**。

**修正**：删掉这一步。批次 2 只动 `FavoriteController` + `UserService`。

---

### 🟡 D-17：B-06 同时保留 `DELETE` 和 `POST` 是长期维护负担

前后端同仓、同一次发布，没有灰度需求。保留两套 = 两处路由、两套测试、两个入口，而且**旧的那套永远没人会删**。

**修正**：要么一次切换（后端加 POST、前端切过去、同时删掉 DELETE 版本），要么**不做**（纯局域网没有会吞 body 的中间层，`DELETE` + body 在 `fetch` 下是合法的）。本文档选**一次切换**，不留兼容层。

---

### 🟡 D-18：`isTvOnline()` 的语义不能直接拿来当「TV 在播」

```java
WsBroadcaster.java:129-134
public boolean isTvOnline() {
    if (!sessionTypes.containsValue("tv")) return false;
    return playerRegistry.connectedPlayerCount() == 0 || playerRegistry.activeSessionId().isPresent();
}
```

注意第二个 return：**没有任何播放端连接时返回 true**。所以 `!isTvOnline()` 不等于「TV 不能播」。看门狗必须组合两个条件（TV 播放端 + Web 租约），v2 的表格里写了但文字部分只提 `!isTvOnline()`，容易实现成错的。

**修正**：判定条件明确写成两个 `AND`（见第七节批次 1.3）。

---

### 🟡 D-19：批次 2 的空白 token 行为会被无意改掉

```java
FavoriteController.java:149-153
private Long requireUser(String clientToken) {
    if (clientToken == null || clientToken.isBlank()) throw new ApiException("INVALID_ARGUMENT", "缺少 clientToken");
    return userService.resolveUserId(clientToken);
}
```

改只读时如果直接 `return userService.resolveExistingUserId(token)`，**空白 token 会从 400 变成 200 + `[]`**。前端 `user.clientToken` 正常情况下非空，但这是契约变更，必须显式决定。建议保留 400（参数缺失仍是客户端错误）。

---

## 六、影响面与回归分析（按批次）

> 「受影响的地方」= 这次改动会碰到的、**v2 没考虑**的既有行为。

| 批次 | 直接改动 | 会被波及的既有行为 | 回归风险 | 处置 |
| --- | --- | --- | --- | --- |
| 1 播放自愈 | 新增看门狗 + 前端重放 | ①`TvOfflineWatcher` 的 180s 清空 ②`recoverAfterRestart` 的重启续播 ③单线程调度器上的 12 个定时任务 ④TV 每秒 `progress` 的行锁 ⑤`authorizesPlaybackReport` 的 generation 校验 | **高** | D-01/02/03/04 四条修正全部落地；测试必须覆盖「重启后不暂停」「TV 在线不动手」 |
| 2 收藏只读 | `FavoriteController` + `UserService` | ①空白 token 的 400 语义 ②`FavoriteControllerTest` 的 6 个用例 ③`App.vue` 启动即注册用户（真实场景下该分支几乎不可达） | 中 | D-19 保留 400；改 setUp |
| 3 登录限流 | `AdminAuthService` | ①冷却期内的计数是否继续叠加 ②错误码从 429 变 401 后前端提示 ③`MAX_TRACKED_CLIENTS` 溢出分支 | 中 | 冷却期内**不再叠加计数**；错误码保持「错密码仍 429」 |
| 4 超时 | `client.js` 全部调用 | ①35 处长耗时调用 ②`requestBlob` ③已有 `signal` 的 11 个调用点 ④歌词加载 | **高** | D-05 默认 0；D-06 横幅不进文档流；D-07 不动歌词 |
| 5 状态一致性 | 5 个前端点 | ①`favorites.load` 的 `loadedFor` 缓存语义 ②`DashboardView` 的 `scanPollGate` ③新 store 的 30s TTL 会让数字变旧 | 中 | `hydrate` 必须同时写 `loadedFor`；轮询改成递归 `setTimeout` 而非加公式 |
| 6 契约补全 | 后端 4 处 + 前端 3 处 | ①`AudioLayoutDto` 同时被 **Android TV** 解析 ②`AudioLayoutEditor` 提交体 ③批量删除的路由 | 中 | TV 端 `ignoreUnknownKeys=true`（全部 9 处），加字段安全；`source` **不能**进提交体 |
| 7 渲染体积 | 4 处 | ①`serveFile` 被歌词共用 ②音量默认值 ③封面被重新刮削后不刷新 | 中 | D-10 只给封面；D-11 改后端默认 |
| 8 可访问性 | 7 弹窗 + 8 小项 | ①`DialogHost` 的 `inert` ②`:deep()` 的全局污染 ③9px→11px 的换行 | 中 | D-12 让位判断；D-13 逐文件 |
| 9 清理 | 4 项 | ①`orderedSongIds` 是否被模板用 ②`lyricState` 不能删 | 低 | 删前 grep，已核实只剩测试引用 |

### 6.1 一条被 v2 完全漏掉的影响：`AudioLayoutDto` 不是只给网页用

`AudioLayoutDto` 会经由 `PlaybackDescriptor` 下发给 **Android TV**（`android-tv/.../net/Models.kt:129-137`）。B-12 要加 `source` 分量，必须确认 TV 不会因未知字段崩。

**已核实安全**：TV 端 9 处 `Json` 配置**全部**带 `ignoreUnknownKeys = true`（`AppConfig.kt:27`、`MediaApi.kt:44`、`KtvSocket.kt:66`、`LibraryApi.kt:8`、`FileSourceResolver.kt:24`、`DiscoveryProtocol.kt:21`、`ControlRequestBuilder.kt:9`、`KtvResponseClassifier.kt:39`）。加字段不会破坏旧版 TV。

但反过来有一条限制：**`source` 只能读、不能写**。`AudioLayoutEditor` 的 `draft` 是直接当提交体用的（`createAudioLayoutDraft(props.song.audioLayout)` → `adminUpdateAudioLayout(fileId, draft)`），所以 `source` **不能**放进 `createAudioLayoutDraft` 的返回对象，否则会被当作写字段提交。必须另做一个只读的 `audioLayoutSourceLabel`。

---

## 七、修订后的 TDD 计划

### 执行纪律

```
1. 先写测试 → 跑 → 必须是红的（且红的原因是你预期的那个）
2. 改实现 → 跑该测试 → 绿
3. 跑该模块全部测试 → 绿
4. 跑全量 → 绿
5. 提交（一个批次一个 commit）
```

**基线命令**：

```bash
cd h5 && npm test                     # 前端：68 文件 / 622 用例（2026-11-20 实测 10.25s）
cd backend && ./mvnw test             # 后端：248 个测试类（**动工前先跑一次确认绿**，v2 未执行）
```

---

### 批次 1 · 播放孤儿自愈（A-01）——已按 D-01~D-04 重写

#### 1.1 问题重新定义

| 场景 | 现状 | 目标 |
| --- | --- | --- |
| TV 离线 + **有手机在线** | 永不清（`TvOfflineWatcher.java:95`） | 45s 后暂停 + toast |
| WEB 播放端离线 | 无人接管 | 45s 后暂停 + toast |
| TV 离线 + 无手机 | 180s 清空队列（**保持不变**） | 不重复提示 |
| 服务刚重启 | `recoverAfterRestart` 置 playing | **180s 内不判定** |

#### 1.2 判定条件（三个 AND，缺一不可）

```
orphan =
    player.state == "playing"
 && player.currentQueueId != null
 && !hasActiveTvPlayer
 && !webPlayerCoordinator.status(false).active()
```

其中 `hasActiveTvPlayer` **不能**直接用 `isTvOnline()`（D-18）：

```java
// 正确写法：TV 播放端存在且处于 ACTIVE
boolean hasActiveTvPlayer = broadcaster.isTvOnline()
        && playerRegistry.activeSessionId().isPresent();
```

宁可偏保守（判不出就认为有人在播、不动手），也不要误杀。

#### 1.3 四道保险（对应 D-02 / D-03）

| 保险 | 实现 | 防的是什么 |
| --- | --- | --- |
| 非锁定读 | 判定用 `playerRepo.findById(1L)`，**不要** `getSingletonForUpdate()` | 和 TV 每秒的 `progress` 抢写锁 |
| 连续两拍 | 内存 `orphanSince`，连续 2 次 tick（20s）都命中才动手 | 切歌瞬间的抖动 |
| 启动宽限 | 内存 `startupGraceUntil = boot + 180s`，且要求「启动后至少有过一次客户端连接」 | 把 `recoverAfterRestart` 刚恢复的歌暂停 |
| 提示不重复 | 仅当 `broadcaster.connectedPhonesCount() > 0` 才发 toast | 无手机时 180s 清空会自己发提示 |

#### 1.4 广播事件要对齐现有实现

`ControlController.java:122-126` 里 pause 广播的是 **`PLAYER_STATE`**（全量快照）。看门狗必须发同一个事件，否则前端 `player.js` 的 `applySnapshot` 分支不会更新。

```
broadcaster.broadcast(WsEvent.of(WsEvent.PLAYER_STATE, snapshotService.snapshot()));
broadcaster.broadcast(WsEvent.of(WsEvent.TOAST, Map.of("text", "播放端已离线，已暂停")));
```

#### 1.5 测试先行（后端）

`backend/src/test/java/com/homektv/queue/PlaybackWatchdogTest.java`（新建）

```java
// 必须为红的 6 条
void pausesWhenTvOfflineButPhoneConnected();      // 场景二
void pausesWhenWebHostLeaseExpired();             // 场景三
void doesNotPauseWhenTvPlayerActive();            // 场景一（TV 在线）
void doesNotPauseWhenWebLeaseActive();            // 场景一（网页在播）
void doesNotPauseWithinStartupGrace();            // D-03：重启后 180s 内不判定
void noToastWhenNoPhoneConnected();               // 无手机时交给 TvOfflineWatcher
void noopWhenStateIsIdleOrPaused();               // 幂等
```

时钟用 `Clock` 注入（照抄 `WebPlayerCoordinator(Clock, Duration)` 的写法，`WebPlayerCoordinator.java:45`），**不要**用 `Thread.sleep`。

#### 1.6 测试先行（前端）

`h5/src/api/ws.replay.test.js`（新建）

```js
it('queues finished report when socket is not open', () => { /* send 返回 false → 入队 */ })
it('replays with the CURRENT generation, not the stale one', () => {
  // 断线时 generation=3 → 入队
  // 重连后 generation=7
  // 期望发出的载荷里 generation === 7     ← D-04 的核心断言
})
it('drops pending report when role becomes STANDBY', () => {
  // 重连后 webPlayerGeneration=0，reportFinished 直接 return，队列清空且不报错
})
```

#### 1.7 实现步骤

```
1. ws.js: send() 返回 boolean（OPEN 且未抛异常 → true）
2. ws.js: 新增 queueReport(queueId) / replayPending()
   - replayPending 只在 onStatus(true) 且 webPlayerGeneration > 0 时执行
   - 重放调用 player.reportFinished(id) 重新构造载荷（不要存消息体）
3. player.js: reportFinished / reportPlayError 判断 send 返回值
4. 新建 backend/.../queue/PlaybackWatchdog.java
   - @Scheduled(fixedDelayString = "${home-ktv.playback.watchdog-interval-ms:10000}")
   - 依赖：PlayerStateRepository、PlaybackService、WsBroadcaster、WebPlayerCoordinator、ActivePlayerRegistry、SnapshotService
   - 内存字段：orphanSince、startupGraceUntil、seenAnyClient
5. KtvWebSocketHandler / WsBroadcaster: 首次注册会话时通知 watchdog.seenAnyClient = true
6. KtvMediaSurface.vue: 停滞检测 → **本轮不做**（误伤长静音前奏，收益不抵风险）
```

#### 1.8 修改范围

| 文件 | 改动 |
| --- | --- |
| `h5/src/api/ws.js:228-233` | `send` 返 boolean；新增 `queueReport` / `replayPending` |
| `h5/src/stores/player.js:287-308` | 上报失败入队（只存 queueId） |
| `backend/.../queue/PlaybackWatchdog.java` | **新建** |
| `backend/.../queue/PlaybackService.java` | **不改**（复用 `pause()`） |
| `backend/.../ws/KtvWebSocketHandler.java` | 加一行 `watchdog.markClientSeen()` |
| `application.yml` | 新增 `home-ktv.playback.watchdog-interval-ms: 10000` |

#### 1.9 验收

- 关掉 TV 应用、手机保持连接 → **45–65s** 内变 `paused`，手机收到 toast，队列**不丢**
- 关掉 TV 应用、手机也退出 → 180s 后队列被清空（**现有行为不变**），且**只有一条** toast
- 重启后端、不打开任何客户端 → 恢复出来的歌**不会被暂停**（宽限期内）
- 浏览器当播放器时关标签页 → 45–65s 内 `paused`

---

### 批次 2 · 收藏接口只读化（A-02）——已按 D-16 / D-19 收窄

#### 2.1 测试先行

```java
void listDoesNotCreateUserForUnknownToken();      // 关键：insertIfAbsent 调用次数为 0
void idsDoesNotCreateUserForUnknownToken();
void blankTokenStillRejected();                   // D-19：空白 token 仍 400
void addStillCreatesUser();                        // 写端点行为不变
```

**现有冲突**：`FavoriteControllerTest.java:33-36` 用匿名子类覆写 `resolveUserId` 返回 `7L`。新增 `resolveExistingUserId` 后覆写失效，**6 个用例会全红**，必须同步改 setUp。

#### 2.2 实现步骤

```
1. UserService 新增（只读，不 insert）：
   @Transactional(readOnly = true)
   public Long resolveExistingUserId(String clientToken)
2. FavoriteController 拆两个入口：
   - requireExistingUser(token)  // list / ids：空 token 仍抛 INVALID_ARGUMENT；查不到返回 null → List.of()
   - requireUser(token)          // add / remove：保持 insertIfAbsent
3. RoomHostService **不动**（D-16）
```

#### 2.3 修改范围

`FavoriteController.java:59,86,148-154`、`UserService.java:25-32`、`FavoriteControllerTest.java:31-39`

#### 2.4 语义变更（已拍板 A）

未注册 token 查收藏 → `[]`（200）。前端 `songs.value = Array.isArray(res) ? res : []` 天然兼容，**前端零改动**。

> 补充事实：`App.vue` 在 `onMounted` 里就会 `api.registerUser(...)`，所以「用户存在但没收藏」是常态，「用户根本不存在」只在注册失败时出现。这条的实际影响面比 v1 估的小。

---

### 批次 3 · 登录限流修正（A-03）

#### 3.1 现状与目标

```java
// 现在（AdminAuthService.java:66-80）
purgeExpired(now);
enforceCooldown(key);            // ← 先判冷却，正确密码也在这里被 429
if (!MessageDigest.isEqual(expected, supplied)) { recordFailedLogin(key, now); throw INVALID; }
```

目标：**正确密码永远能进；错密码在冷却期内仍然 429，且不再延长冷却**。

#### 3.2 测试先行

```java
void correctPasswordSucceedsDuringCooldown();     // 现在红
void wrongPasswordStillRejectedDuringCooldown();  // 防打穿
void cooldownDoesNotExtendOnRepeatedAttempts();   // 冷却窗口不因反复尝试被无限续期
```

#### 3.3 实现步骤

```
purgeExpired(now);
boolean inCooldown = isCoolingDown(key);
if (!MessageDigest.isEqual(expected, supplied)) {
    if (inCooldown) throw new ApiException("ADMIN_AUTH_RATE_LIMITED", ...);  // 不再 record，避免续期
    recordFailedLogin(key, now);
    throw new ApiException("ADMIN_AUTH_INVALID", ...);
}
failedLogins.remove(key);
... 签发 token
```

**已拍板**：不加 `X-Forwarded-For`、不加 `trust-proxy`。用户确认纯局域网无反代，`getRemoteAddr()` 即真实 IP。原 D-06 / V8 风险**删除**，不再作为待验证项。

#### 3.4 修改范围

仅 `AdminAuthService.java:63-95,101-118`。`AdminAuthController.java:28` 与 `AppProperties` **不动**。

#### 3.5 风险

唯一风险是暴破成本下降（冷却期内可继续试密码）。用 3.2 第 2 条锁死。纯内网前提下可接受。

---

### 批次 4 · 前端网络层超时与离线（A-04）——已按 D-05~D-07 重写

#### 4.1 核心决定：默认不超时

```
timeoutMs 默认值 = 0（不设超时）    ← 与今天行为完全一致，零回归
只有显式传入 timeoutMs > 0 才生效
```

理由见 D-05：35 处长耗时调用一旦被 15s 砍断，会从「等待」变成「后端仍在跑 + 用户重试 → 重复任务」。

#### 4.2 需要显式加超时的接口（只加读接口）

| 超时 | 端点 | 理由 |
| --- | --- | --- |
| **10s** | `getQueue` / `control` / `favorites` / `favoriteIds` / `searchSongs` / `songDetail` / `browse*` / `playlists` / `history` / `ranking` / `newSongs` / `roomHostStatus` | 本地 DB 查询，正常 <200ms；10s 只为兜住「网线拔了」 |
| **10s** | `adminStatus` / `adminSongs` / `adminSong` / `adminArtistsPage` / `adminAiTasks` / `adminScanProgress` / `adminSourceTranscodeProgress` / `adminArtistAvatarProgress` | 后台轮询类，挂起会让轮询堆积 |
| **0（不变）** | `adminStartScan` / `adminDiagnostics` / `adminDownloadDiagnostics` / `adminAi*` 写操作 / `adminAnalyze*` / `adminScrape*` / `adminTest*` / `adminStartSourceTranscode` / `adminIktvFusion*` / 全部上传 | 合法长耗时（见 D-05 表） |

#### 4.3 测试先行

`h5/src/api/client.timeout.test.js`（新建）

```js
it('does not time out by default')                 // 不传 timeoutMs → 永不 reject（保持现状）
it('aborts request after explicit timeout')        // code === 'TIMEOUT'
it('keeps caller signal working with timeout')     // 两个 signal 都能中止
it('does not leak abort listener on success')      // 成功路径清掉 listener + timer
it('does not let timeout be mistaken for abort')   // error.name !== 'AbortError'
```

#### 4.4 实现步骤

```
1. client.js 新增 linkAbortSignals(callerSignal, timeoutMs)
   ⚠️ 不用 AbortSignal.any（Chrome 116+），手写 controller + addEventListener + setTimeout
   ⚠️ timeoutMs <= 0 时直接返回 callerSignal（零开销、零行为变化）
2. request / requestBlob 从 options 里**解构出** timeoutMs（不能留给 fetch，未知字段会污染 init）
3. 按 4.2 表格给读接口加 timeoutMs
4. 离线状态：新增 stores/netStatus.js
   - 用 position:fixed 的顶部浮层（**不要**插进 App.vue 文档流，见 D-06）
   - 或直接扩展现有 `.ws-pill`（KtvHeaderBar.vue:41）
5. lyricLoader.js **不改**（D-07）
```

#### 4.5 修改范围

`h5/src/api/client.js:23-81` 与各端点声明、`h5/src/stores/netStatus.js`（新建）、`h5/src/components/ktv/KtvHeaderBar.vue:41,228`

#### 4.6 风险

- `createLatestRequest` 的 `signal` 必须与超时 signal **合并后都能中止**（4.3 第 3 条锁死）
- 不要在 `fetch` 的 init 里残留 `timeoutMs` 字段

---

### 批次 5 · 前端状态一致性（B-01 / B-04 / B-05 / B-21 / B-22）

| 项 | 测试先行 | 实现 | 修改范围 |
| --- | --- | --- | --- |
| **B-04**（先做） | `IktvFusionView.test.js`：点「恢复融合」后标题不含「回滚」 | 拆 `resumeResult` / `rollbackResult` 两个 ref | `IktvFusionView.vue:72-84,99,139,160` |
| **B-01** | `api.favorites` 只调 1 次 | 用首次结果 `res.map(s=>s.id)` 灌 store；新增 `favorites.hydrate(ids)`，**必须同时写 `loadedFor` 且清 `loadFailed`** | `KtvThreeColumnBrowser.vue:1053-1068`、`stores/favorites.js` |
| **B-05** | 卸载后 advance 3s，`message` 不被写入 | `const t = setTimeout(...)` + `onUnmounted(()=>clearTimeout(t))`（需补 `onUnmounted` 导入） | `AiTaskPanel.vue:51,87` |
| **B-21** | 4 视图各 mount 一次，总调用 ≤ 1 | 新增 `stores/adminSourceStats.js`，30s TTL | 4 视图 + 新 store |
| **B-22** | 连续 5 次 poll 间隔递增 | **改结构，不是加公式**：`setInterval` → 递归 `setTimeout`，保留 `scanPollGate` | `DashboardView.vue:139,180-188` |

**B-21 建议不做**：4 次 `size:1` 请求每次几百字节，加一个带 TTL 的 store 意味着「扫描刚结束、数字还是旧的」这类新问题。排到 P3。

**B-22 的关键**：现有实现已经是 `setInterval(1000)` + `scanPollGate` + `scanPollRetryTimer` 三件套。直接改成 `delay = min(1000*1.2^n, 5000)` 会和 interval **同时触发**，产生双倍请求。必须先把 `setInterval` 换成递归 `setTimeout`。

---

### 批次 6 · 后端契约与前端信息补全（B-10 / B-03 / B-06 / B-11 / B-12 / B-02）

| 项 | 测试先行 | 实现 | 修改范围 |
| --- | --- | --- | --- |
| **B-10** | 不带 `effect_id` → 期望 400 `INVALID_ACTION`（现状 500） | 先 `String id = req.strParam("effect_id")`，判空抛 `ApiException` | `ControlController.java:166-170` |
| **B-03** | `?retryFailed=true` 仍 200 | 删形参（已核实 TV 端 0 处传参，Spring 忽略未知 query） | `PlaybackController.java:23` |
| **B-06** | 前端断言新为 POST | **一次切换**：后端加 `POST /admin/songs/batch-delete`、`POST /admin/source-library/batch-delete`，前端切过去，**同时删掉 DELETE 版本** | `client.js:151,160`、`AdminScanController.java:240,381` |
| **B-11** | `SONG_NOT_READY` → 「正在扫描或媒体探测未完成」 | 前端建 `UNAVAILABLE_REASON_LABELS`（D-08），未知码兜底「当前不可点」 | `KtvThreeColumnBrowser.vue:389-393` |
| **B-12** | `from()` 带出 `source` | DTO 加 `source` 分量；**前端只读展示**，不进 `createAudioLayoutDraft` | `AudioLayoutDto.java:11-37`、`AudioLayoutEditor.vue:72` |
| **B-02** | `GROUP` 渲染为「组合」 | 建 `ARTIST_KIND_LABELS` 五值映射；**默认批量范围维持 PERSON**（D-09） | `ArtistLibraryView.vue:216,219` |

**B-12 的额外约束**（见 6.1）：`source` 不能进 `createAudioLayoutDraft` 的返回值，否则会被当写字段提交给 `PUT /admin/files/{id}/audio-layout`。另做一个 `audioLayoutSourceLabel(source)` 纯函数。

---

### 批次 7 · 渲染与体积（B-08 / B-09 / B-13 / B-18）

| 项 | 测试先行 | 实现 | 修改范围 |
| --- | --- | --- | --- |
| **B-08** | `/cover/{id}` 响应含 `Cache-Control`，`/lyric/{id}` **不含** | **只在 cover 分支**加 `max-age=1d, public`（D-10）；`serveFile` 不动 | `SongController.java:158-166` |
| **B-09** | WEB_HOST 上报失败时渲染 poster 兜底 | `reportPlayError` 返 boolean；false 时置 `mirrorStreamFailed` | `KtvMediaSurface.vue:418-422`、`player.js:296-308` |
| **B-13** | 无（纯视觉） | 抽 `ktvControlSteps.js` 的 `VOLUME_STEP=8`；**后端 `PlayerState.volume` 默认 100→60**（D-11） | `KtvBottomDock.vue:37,75`、`KtvMediaSurface.vue:108-109`、`PlayerState.java:34` |
| **B-18** | 无 | 两处 `max-height` 前补 `100vh` 版本（D-14）；`KtvKioskView:550` 不动 | `KtvThreeColumnBrowser.vue:1612,1621` |
| ~~B-07~~ | — | **关闭**（D-15，WARN 已存在） | — |
| ~~B-26~~ | — | 不做（已拍板） | — |

**B-08 为什么不是 7 天**：封面会被重新刮削替换。7 天 + 无 ETag = 用户换完封面看不到效果。1 天是折中。

**B-13 的方向说明**：改后端默认值只影响**新建的 `player_state` 行**，老库不受影响、不改表。

---

### 批次 8 · 可访问性（B-16 ~ B-20、B-23 ~ B-25）

| 项 | 实现 | 修改范围 |
| --- | --- | --- |
| **B-19** 焦点陷阱 | 抽 `composables/useFocusTrap.js`，**必须加「上层有对话框则让位」判断**（D-12） | `AdminLayout.vue:25`、`AiLibraryView.vue:109,134,162`、`ArtistLibraryView.vue:28,30`、`AudioLayoutEditor.vue:4` |
| **B-20** tablist | **推荐删 `role="tablist"`**，退化为普通按钮组 | `KtvThreeColumnBrowser.vue:8`、`KtvSoftKeyboard.vue:18` |
| **B-16** range label | 3 处补 `aria-label` | `KtvMediaSurface.vue:81` 等 |
| **B-17** 对比度 | `#94a3b8`→`#5b6878`、10px→11px，**逐文件改 4 处**（D-13） | AiLibraryView:443、ArtistLibraryView:259、DashboardView:250、KtvLibraryView:186 |
| **B-18** dvh | 见批次 7 | — |
| **B-23** 假按钮 | `role="button" tabindex="0" @keydown.enter/space`（`.marquee-box` 确有 `@click`） | `KtvHeaderBar.vue:26` |
| **B-24** 复选框 | 补 `aria-label="全选当前页"` + `:aria-label="'选择 '+song.title"` | `KtvLibraryView.vue:17,18` |
| **B-25** 字号 | 9px→11px（3 处 KTV 控件） | `KtvBottomDock.vue:290`、`KtvMediaSurface.vue:631`、`KtvSoftKeyboard.vue:286` |

#### 8.1 `useFocusTrap` 的关键实现

```js
function onKeydown(e) {
  if (e.key !== 'Tab') return
  // D-12：全局对话框在最上层时直接让位，不要抢焦点
  if (document.querySelector('.dialog-mask .dialog-card')) return
  ...原有陷阱逻辑...
}
```

#### 8.2 风险

B-17 改色后需**肉眼复核后台 5 个页面**。B-25 字号变大会导致 dock 文案换行，需在 1080p/720p 各看一眼。

---

### 批次 9 · 清理（C-01 ~ C-04）

| 项 | 动作 | 注意 |
| --- | --- | --- |
| **C-01** | 删 `composables/useAsyncResource.js` + 同名 test | **只删这一个**，`lyricState.js` 有生产引用（`KtvMediaSurface.vue:141`） |
| **C-02** | 删 `player.js:53-56` `orderedSongIds` + 对应用例 | 删前 grep 模板（`player.test.js:217-228` 是唯一引用） |
| **C-03** | 去掉 `ktvCatalogPresets.js:59,60` 的 `export` | 纯语法 |
| **C-04** | 抽 `api/query.js` 的 `toQuery()`，替换 5 处 | 纯重构，5 处回归用例 |

---

## 八、执行顺序与依赖

```
批次 1（A-01 播放自愈）   ← 唯一有设计难度，且依赖 D-01~D-04 四条修正
   ↓
批次 2（A-02 收藏只读）   ← 语义已定，30 分钟
批次 3（A-03 登录限流）   ← 只动 1 个文件
   ↓
批次 4（A-04 超时）       ← 前端网络层，先跑通「默认 0」再加白名单
   ↓
批次 5（B-04 先修，其余随意；B-21 建议跳过）
   ↓
批次 6 / 7 / 8 可并行
   ↓
批次 9（最后做，避免中途删了又被引用）
```

**提交节奏**：批次 1–4 各一个 commit（每批跑全量）；批次 5–9 合并成 2–3 个 commit。

---

## 九、已拍板决策（2026-11-20 23:58）

| # | 决策点 | 结果 | 依据 |
| --- | --- | --- | --- |
| 1 | A-02 未注册 token 查收藏 | 返回 `[]` | 404 会让正常用户看到「收藏读取失败」假错误 |
| 2 | A-03 反代 IP 支持 | 不做，只改顺序 | 纯内网无反代；开关误开可被伪造 XFF 绕过 |
| 3 | B-03 `retryFailed` | 删形参 | TV 端 0 处传参；Spring 忽略未知 query |
| 4 | B-07 出站队列 | **关闭该项**（v3 改） | 已有 WARN；积压量拿不到；断连能自愈 |
| 5 | B-26 首屏拆包 | 不做 | 首屏实测 1.14MB，局域网 ~150ms |

### v3 新增的待确认项（4 条）

| # | 问题 | 选项 | 我的建议 |
| --- | --- | --- | --- |
| 6 | 批次 1 中「TV 离线且无手机」时，45s 暂停 + 180s 清空两次动作是否都要 | A. 都保留，只发一次 toast<br>B. 该场景跳过暂停，直接等 180s 清空 | **B**（更少动作 = 更少意外），实现上就是给看门狗加一个 `connectedPhonesCount()==0 && !isTvOnline()` 的跳过分支 |
| 7 | 11 张首屏 PNG 转 WebP | A. 做（1h，零风险，1.14MB→~400KB）<br>B. 不做 | **A**，但它和拆包是两回事，不进本轮 |
| 8 | B-06 批量删除怎么切 | A. 一次切换、删旧路由<br>B. 双路由兼容一版<br>C. 不做 | **A** |
| 9 | B-21（4 视图重复拉统计） | A. 不做<br>B. 做共享 store | **A**，降 P3 |

---

## 十、回归风险登记（v3 更新）

| 风险 | 触发条件 | 检测手段 |
| --- | --- | --- |
| `TvOfflineWatcher` 与新看门狗同时动作 | 批次 1 | 验收必须跑「TV 离线 + 无手机」与「TV 离线 + 有手机」两条路径 |
| `recoverAfterRestart` 被看门狗反杀 | 批次 1 | 用例 `doesNotPauseWithinStartupGrace` |
| 单线程调度器被看门狗拖慢 | 批次 1 | 观察 `PlaybackPressureMonitor` 日志间隔是否仍 ~500ms |
| `FavoriteControllerTest` 6 个用例 | 批次 2 | 改 setUp 前先跑一次记录基线 |
| 冷却期计数被无限续期 | 批次 3 | 用例 `cooldownDoesNotExtendOnRepeatedAttempts` |
| 35 处长耗时调用被超时砍断 | 批次 4 | 默认 0；逐条核对 4.2 表格 |
| 离线浮层挤破 `100dvh` | 批次 4 | 720p/1080p 各看一眼，确认无纵向滚动 |
| `favorites.hydrate` 漏写 `loadedFor` | 批次 5 | 会在其他页面触发第二轮全量请求 |
| 轮询改造产生双倍请求 | 批次 5 | 用例断言「5 次 poll 内总请求数 == 5」 |
| `AudioLayoutDto.source` 被写回后端 | 批次 6 | `createAudioLayoutDraft` 的返回对象不得含 `source` |
| 旧版 Android TV 因新字段崩溃 | 批次 6 | **已核实安全**：9 处 `ignoreUnknownKeys=true` |
| 歌词被缓存 7 天 | 批次 7 | 用例断言 `/lyric/{id}` **无** `Cache-Control` |
| `useFocusTrap` 抢走对话框焦点 | 批次 8 | 用例：弹窗内打开 confirm，Tab 仍在 confirm 里循环 |
| `:deep()` 污染 SourceLibraryView | 批次 8 | 采用逐文件方案即规避 |
| `ws.test.js:156-169` 断言「ignores pong」 | 若动 B-15 | 改前先看该用例，改后同步改断言 |
| `client.test.js` 断言 DELETE method | 批次 6 B-06 | 切换后同步改断言 |
| `player.test.js:217-228` | 批次 9 C-02 | 删 getter 时同步删用例 |

---

## 附：仍无法静态判定的 5 项

| # | 项 | 为什么判不了 |
| --- | --- | --- |
| 1 | 720p / 1080p 实际溢出 | 需按分辨率截图量 `scrollWidth > clientWidth` |
| 2 | 触控区是否达 44×44 | 受 padding/`::before` 影响，需 `elementFromPoint` |
| 3 | 对比度最终值 | 有 CSS 变量与 `backdrop-filter` 叠加，要取渲染像素 |
| 4 | 收藏页 N=1万 真实耗时 | 需压测数据 |
| 5 | 10 个零调用端点是漏接还是砍需求 | 需 PRD / 需求变更记录 |

这五项**不影响上面 33 条的成立性**，但会影响「值不值得投入」的判断。
