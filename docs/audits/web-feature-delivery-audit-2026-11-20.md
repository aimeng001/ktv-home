# 网页端功能完整性交付审计报告

**Feature Delivery Audit — H5 / Web 端**

> ## ⚠️ 勘误与降级声明（2026-11-20 复审后追加）
>
> 本文档已经过一轮**对抗性复审**，结论见 `docs/audits/web-feature-remediation-tdd-2026-11-20.md`。复审推翻了本文档的部分结论，阅读时请以复审文档为准：
>
> | 本文档条目 | 复审结论 |
> | --- | --- |
> | **P0-01 升降调空实现** | ❌ **不成立**。产品已明确声明"网页端不支持"，`KtvModals` 弹窗内已有常驻说明，`ControlController` 与 `makeControls` 两侧一致未提供。属能力边界，非缺陷。残留问题仅为 `stores/player.js` 中的 pitch 死代码（P2） |
> | **P0-02 注册失败导致点歌失效** | ⚠️ **夸大，降为 P1**。后端 `UserService.resolveUserId` 会 `insertIfAbsent` 自动补建用户，**点歌不会失败**。真实危害是 `serverUserId` 被清空导致房主身份永远识别为 false + 昵称为默认值 |
> | **P1-03 收藏态全错** | ⚠️ **降为 P2**。收藏列表有独立的 `loadFavorites()` 与 `loadError` 提示，`favorites.load()` 只同步心形图标且失败不缓存、下次自动重试 |
> | **P1-05 进度永久停滞** | ⚠️ **降为 P2**。它不是轮询，是操作后单次拉取，下一次操作即刷新 |
> | **P1-06 表格中的 `scan/rebind`** | ❌ **该端点不存在**（`rebind` 仅为 `LibraryScanCoordinator` 内部方法，无 REST 映射），该行作废 |
> | **P1-06 表格中的 `imports/{id}/source`** | ❌ **方法错误**，实为 `@DeleteMapping`（删除源文件记录），非 GET"查看详情" |
> | **P2-04 昵称不一致** | ⚠️ **低估，升为 P1**。`user.register()` 会清空 `serverUserId` + `registrationError`，失败后用户被"降权"为无错误信息的未就绪态 |
> | **"6 处静默失败"** | ⚠️ 实际仅 3 处为 P1（`KtvQueueDrawer`×2、`KtvMediaSurface`×1），其余 3 处为 P2 |
>
> **复审后的 P0 数量为 0。** 本报告第三章的两个 P0 条目请勿直接据以排期，请改用复审文档第二章的 R-01 ~ R-09 清单。

| 项 | 内容 |
| --- | --- |
| 审计对象 | `h5/` 前端（Vue 3.5 + Vite 6 + Pinia + vue-router 4），`base=/m/`，构建产物写入 `backend/src/main/resources/static/m` |
| 审计范围 | 全部 **21 条路由**（12 条点歌端 + 9 条后台），19 个交互组件，**309 处事件绑定**，约 245 个 `<button>` |
| 交叉验证 | 前端调用链 ↔ `api/client.js` ↔ 后端 40+ 个 Controller ↔ 持久化服务（`AdminService` / `MediaImportService` / `SettingService` / `WishController`） |
| 审计方法 | 纯静态源码走查 + 端点双向集合比对（前端 99 条调用 ↔ 后端 134 个端点）+ 落库路径追查 |
| 审计日期 | 2026-11-20 |
| 输出约束 | 本报告为唯一交付物；**未修改任何源码**，未改动 `PLAN.md` / `TASK_STATE.md` |

> **术语备注**
> - **空实现（Empty Implementation）**：控件有点击事件、有视觉反馈，但没有产生任何实际业务效果。
> - **静默失败（Silent Failure）**：`catch` 块内不做任何处理（空块或只吞掉错误），用户与日志都得不到失败信号。
> - **零入口（Unreachable Feature）**：后端端点已实现并可通过测试，但网页端没有任何控件或调用链可以触达。
> - **PIP**：Picture-in-Picture，此处指点歌端底部 Dock 内嵌的小尺寸播放窗口。

---

## 一、结论速览

| 级别 | 数量 | 定性 |
| --- | --- | --- |
| **P0** | 2 项 | 可见控件功能为零；主流程静默中断，用户无法感知失败 |
| **P1** | 6 项 | 静默失败（失败后无提示 / UI 状态错误）、交互与无障碍不一致、后端能力在网页端零入口 |
| **P2** | 6 项 | 死代码、冗余 API 封装、状态一致性瑕疵、前瞻性路由风险 |
| **已核查排除** | 14 项 | 疑似缺陷，经证据核对确认实现正确（见第五章） |

**总体判断：**

1. **导航与端点连通性无缺陷。** 21 条路由全部正确注册、全部有可渲染组件、全部有可达入口；前端 99 条 API 调用 **100% 能在后端找到对应端点**，不存在 404 死链接。
2. **后台 9 个管理视图的交付质量显著高于点歌端。** 所有写操作均有 `try/catch + alertDialog` 反馈，服务端有白名单与 `requireManaged` 硬保护，**未发现可污染数据库的写入路径**。
3. **唯一的功能级硬缺失集中在「升降调」。** 它是一个从 UI → 父组件 → 播放引擎 → 后端指令的**全链路未实现**能力，却在 3 个位置暴露了 7 个可点击按钮。
4. **主要风险类型是「静默失败」。** 共 6 处 `catch` 空块，其中 2 处会直接导致界面状态与实际不符（房主状态、收藏状态）。

---

## 二、审计范围与方法

### 2.1 路由全景（21 条）

点歌端 12 条路由 **全部复用同一个壳组件** `views/KtvKioskView.vue`（504 行），由 `applyRouteIntent()` 把 URL 意图映射为 `viewMode`（dashboard / browser）+ `drawerOpen` + `activeModal` + `fullscreen` 四种呈现状态。

| # | 路径 | name | 渲染形态 | 意图映射 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | `/` | entry | dashboard 磁贴首页 | `viewMode=dashboard` | ✅ |
| 2 | `/home` | home | dashboard 磁贴首页 | 同上 | ✅ |
| 3 | `/search` | search | browser，`song_name` Tab + `q` 关键词 | 读 `route.query.q` | ✅ |
| 4 | `/artist/:name` | artist | browser，按 `?mode=` 分流到歌手/语种/主题/演唱形式 | 5 分支齐全 | ✅ |
| 5 | `/browse` | browse | browser，按 `?tab=artists\|languages\|tags` 分流 | `tab` 值映射正确 | ✅ |
| 6 | `/playlists` | playlists | browser，`featured` Tab | — | ✅ |
| 7 | `/playlists/:id` | playlist-detail | browser，`featured` Tab + `playlistId` | — | ✅ |
| 8 | `/recent` | recent-history | browser，`recent` Tab | — | ✅ |
| 9 | `/favorites` | favorites | browser，`favorites` Tab | — | ✅ |
| 10 | `/queue` | queue | 打开已点抽屉（不切视图） | `drawerOpen=true` | ✅ |
| 11 | `/remote` | remote | 打开调音台弹窗（不切视图） | `activeModal='mixer'` | ✅ |
| 12 | `/lyric` | lyric | 进入全屏演唱态 | `fullscreen=true` | ✅ |
| 13 | `/admin/login` | admin-login | 密码登录页 | `meta.public=true` | ✅ |
| 14 | `/admin` | admin-dashboard | 后台框架 + 总览 | 需 `isAuthenticated` | ✅ |
| 15 | `/admin/source-library` | admin-source-library | 源文件库（转码） | — | ✅ |
| 16 | `/admin/ktv-library` | admin-ktv-library | KTV 曲库 | — | ✅ |
| 17 | `/admin/artists` | admin-artists | 歌手库 / 头像 | — | ✅ |
| 18 | `/admin/metadata-scrape` | admin-metadata-scrape | 元数据刮削 | — | ✅ |
| 19 | `/admin/ai` | admin-ai | AI 歌单 | — | ✅ |
| 20 | `/admin/settings` | admin-settings | 系统设置（657 行，控件最多） | — | ✅ |
| 21 | `/admin/songs` | — | `redirect` 到 `admin-ktv-library` | — | ✅ |

**关键验证点（已通过）：** 点歌端 `/search → /favorites → /recent` 之间切换时，组件**不会重建**（`v-if`/`v-else` 只区分 dashboard / browser），仅靠 `props.initial*` 变化驱动。经核实 `KtvThreeColumnBrowser.vue:1254-1283` 存在对 7 个 `initial*` prop 的 `watch`，并在回调中重写本地 `activeTab/keyword/selectedArtist/...` 后调用 `reloadActive()`，**不存在"路由变了但界面没变"的问题**。

### 2.2 控件盘点（19 个含交互的组件）

| 组件 | 事件绑定 | button | input | select | textarea | dialog | link/路由 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `views/admin/AiLibraryView.vue` | 35 | 29 | 7 | 2 | 2 | 3 | 1 |
| `components/ktv/KtvThreeColumnBrowser.vue` | 30 | 30 | 0 | 0 | 0 | 0 | 0 |
| `views/admin/ArtistLibraryView.vue` | 28 | 20 | 5 | 4 | 0 | 2 | 0 |
| `views/admin/MetadataScrapeView.vue` | 28 | 26 | 4 | 1 | 1 | 0 | 0 |
| `components/ktv/KtvMediaSurface.vue` | 24 | 20 | 1 | 0 | 0 | 0 | 0 |
| `components/ktv/KtvModals.vue` | 24 | 19 | 4 | 0 | 0 | 1 | 2 |
| `views/admin/KtvLibraryView.vue` | 24 | 19 | 7 | 3 | 0 | 0 | 0 |
| `views/admin/SettingsView.vue` | 23 | 20 | 23 | 7 | 1 | 0 | 1 |
| `views/admin/SourceLibraryView.vue` | 16 | 12 | 3 | 2 | 0 | 0 | 0 |
| `components/ktv/KtvQueueDrawer.vue` | 14 | 13 | 0 | 0 | 0 | 1 | 0 |
| `components/admin/AiTaskPanel.vue` | 12 | 12 | 10 | 1 | 1 | 0 | 2 |
| `components/ktv/KtvBottomDock.vue` | 11 | 10 | 0 | 0 | 0 | 0 | 0 |
| `components/ktv/KtvHeaderBar.vue` | 9 | 8 | 0 | 0 | 0 | 0 | 1 |
| `components/ktv/KtvSoftKeyboard.vue` | 9 | 8 | 1 | 0 | 0 | 0 | 0 |
| `views/admin/AudioLayoutEditor.vue` | 7 | 5 | 0 | 5 | 0 | 1 | 0 |
| `views/admin/AdminLayout.vue` | 5 | 4 | 0 | 0 | 0 | 1 | 8 |
| `views/admin/DashboardView.vue` | 5 | 5 | 0 | 0 | 0 | 0 | 2 |
| `components/DialogHost.vue` | 4 | 3 | 0 | 0 | 0 | 1 | 0 |
| `components/ktv/KtvBentoDashboard.vue` | 1 | 1 | 0 | 0 | 0 | 0 | 0 |
| **合计** | **309** | **~245** | **65** | **25** | **6** | **10** | **17** |

**组件层级结论：**

- 所有 `@click` 均指向**已定义**的 handler。用正则分别提取 `<template>` 与 `<script>` 区后做全量比对，仅 2 处"疑似未定义"经核实为 `useDialog()` 解构出的 `close` 与 `player.setModeOverride` 别名，**无真正悬空绑定**。
- 全站**无 `window.confirm` / `window.alert` / `window.prompt`**，统一走 `useDialog()` + `DialogHost.vue`（Teleport + `inert` 背景 + 焦点陷阱 + Escape 关闭 + 焦点归还），弹窗体验与可访问性一致。

### 2.3 九项检查点判定口径（沿用本次审计已确认标准）

| 检查点 | 判定方式 |
| --- | --- |
| ① 连接业务逻辑 | 事件 → handler → store/service 调用链是否闭合 |
| ② 真正执行功能 | handler 内是否有真实副作用（API 调用 / 状态变更 / 播放器指令），排除"只 toast" |
| ③ 操作数据库 | 追踪到后端 Service 的 `save/delete/update`，确认是否有 `@Transactional` 与校验 |
| ④ 更新 UI | API 返回后是否回写响应式状态或重新拉取列表 |
| ⑤ 异常处理 | 是否有 `try/catch`，catch 内是否有用户可见反馈 |
| ⑥ 数据校验 | 前端非空/范围校验 + 后端白名单/类型校验 |
| ⑦ TODO/Mock/Placeholder/空实现 | 全量搜 `TODO|FIXME|HACK|mock|暂未实现`，并逐个核对"仅 toast"的 handler |
| ⑧ 死链接/未注册导航 | 路由表 ↔ `router-link/to` ↔ 后端端点集合三方比对 |
| ⑨ 点击无响应/功能缺失/流程中断 | 对照 ② 的结论，以及后端有端点但前端无入口的差集 |

**分级标准：**

- **P0**：主流程阻断或数据风险 —— 死链接/未注册路由、点击完全无响应、点歌/播放/登录流程中断、写入无校验可污染数据库、NAS 只读红线被越过。
- **P1**：功能可用但残缺 —— 无异常处理（失败后 UI 卡死/无提示）、无数据校验、静默失败、占位/半实现可见入口、后端能力零入口。
- **P2**：体验与一致性 —— 无障碍/键盘焦点、loading 缺失、文案与反馈不一致、重复请求、死代码。

---

## 三、P0 级问题

### P0-01 升降调（Pitch）全链路未实现，7 个可见按钮功能为零

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 点歌端全部 12 条路由（壳组件 `KtvKioskView`），涉及 3 个承载组件 |
| **控件名称** | ① `KtvBottomDock` 底部 Dock「伴奏降调」（第 75 行）<br>② `KtvBottomDock` 底部 Dock「伴奏升调」（第 80 行）<br>③ `KtvMediaSurface` 全屏控台「降调」（第 91 行）<br>④ `KtvMediaSurface` 全屏控台「升调」（第 92 行）<br>⑤ `KtvModals` 调音台弹窗「降调」（第 155 行）<br>⑥ `KtvModals` 调音台弹窗「原调」（第 156 行）<br>⑦ `KtvModals` 调音台弹窗「升调」（第 157 行） |

**问题描述**

7 个按钮点击后**只弹出一条 toast 提示，播放音高没有任何变化**。这是本次审计中唯一确认的"可见控件功能完全为零"的功能点，且同时命中检查点 ②（未真正执行功能）、⑦（空实现）、⑨（功能缺失）。

链路逐层验证结果：

| 层 | 现状 | 证据 |
| --- | --- | --- |
| UI 层 | 3 个组件共 7 个按钮**全部存在且可点击** | `KtvBottomDock.vue:75,80`、`KtvMediaSurface.vue:91,92`、`KtvModals.vue:155-157` |
| 事件层 | 3 个组件均声明 `pitch-delta` / `pitch-reset` emits，父组件绑定了 `adjustPitch` / `resetPitch` | `KtvKioskView.vue:37,58,79,80` |
| 父组件层 | **`adjustPitch()` / `resetPitch()` 内只有 `toast(...)`，无任何 store 或 API 调用** | `KtvKioskView.vue`（见下文代码片段） |
| Store 层 | `player.pitchSemitones`（±6 半音）、`adjustPitch`、`setPitch`、`resetPitch` 方法齐全，但**全项目零调用** | `stores/player.js:53,209,320-332` |
| 播放引擎层 | **无任何消费方**。`webAudioChannelRouter.js` 只建了 `createMediaElementSource` + `createGain`，**没有 `detune` / `playbackRate` / pitch-shifter 节点** | `player/webAudioChannelRouter.js:42-45` |
| 后端层 | `ControlController` 的 action `switch` **不含任何 pitch 分支**（只有 order/shuffle/top/cancel/play/pause/stop/seek/restart/next/set_volume/mute/set_vocal/swap_vocal_tracks/effect） | `web/ControlController.java:91-175` |

因此这不是"少写了一行调用"，而是 **UI → 事件 → 状态 → 音频 → 后端指令 五层全部缺失**，只是 UI 先落地了。

**根本原因**

功能按"UI 先出、能力后补"的方式交付，但缺少一个"未实现能力不暴露入口"的收敛动作。`player.pitchSemitones` 字段的存在产生了一种"已经实现"的错觉——它只维护一个数字，没有任何音频侧副作用；`clampPitch()` 把范围限制在 ±6 半音，看起来像成品，实际是孤立状态。

同时 `KtvKioskView` 的两个 handler 用 `toast` 做了"善意兜底"，反而掩盖了缺失：用户得到反馈，但反馈内容与控件承诺不符。

**修改方案**

按"先行止血、再补能力"两步走，两者可独立上线：

**第 1 步（止血，建议立即做）—— 二选一：**

- **方案 A（推荐，成本最低）**：能力未实现前**隐藏入口**，避免"点击无效"的负面体验。
  - `KtvBottomDock.vue`：为两个 pitch 按钮包一层 `v-if="pitchSupported"`，或在 `KtvKioskView` 传入 `:pitch-enabled="false"`（默认 false，能力上线后置 true）。
  - `KtvMediaSurface.vue`、`KtvModals.vue`：同上，用同一个 prop 控制，保证 3 处一致。
- **方案 B（保留入口）**：把 `adjustPitch()` / `resetPitch()` 的 toast 文案从"操作成功"改为明确的"当前版本不支持升降调"，并置为 `:disabled` 视觉态。**不推荐**——留着不可用按钮比隐藏更伤体验。

**第 2 步（补能力，需单独立项）—— 三种实现路径：**

| 路径 | 做法 | 适用模式 | 成本与风险 |
| --- | --- | --- | --- |
| **B1 Web Audio 变调** | 在 `webAudioChannelRouter` 现有 `source → gain` 链路中插入 pitch-shifter 节点（`SoundTouchJS` / Rubber Band WASM / `AudioWorklet` 自研相位声码器）。`<video>` 已被 `createMediaElementSource` 接管，具备插入条件 | `WEB_HOST`（本机主播放端） | 中；需处理延迟与音质，且 `playbackRate` 变速会连带改变时长，必须用变调不变速算法 |
| **B2 服务端预生成** | 后端按 ±N 半音用 `ffmpeg -af "asetrate=...,aresample=...,atempo=..."` 生成临时音轨，前端切源 | 全模式 | 高；转码延迟 + 存储开销，不适合实时调 |
| **B3 指令透传** | 在 `ControlController` 增加 `case "pitch"`，把 `semitones` 广播给 TV 端，由 TV 原生播放器执行 | `TV_CONSOLE`（TV 在线时静音镜像） | 低；但依赖 TV 端是否支持，需先确认 TV 侧能力 |

**推荐组合**：`WEB_HOST` 走 B1，`TV_CONSOLE` 走 B3；`WEB_REMOTE`（纯遥控台）只透传指令、不本地处理。

**无论走哪条路，都必须同时做：**

1. `KtvKioskView.adjustPitch()` 内改为 `player.adjustPitch(delta)` + `controls.setPitch(...)`。
2. 在 `KtvMediaSurface` 与 `KtvModals` 增加**当前音高值回显**（如 `+2`），否则用户无法判断是否生效。
3. 若走 B3，`ControlController` 的 `default` 分支当前是 `throw new ApiException("INVALID_ACTION", ...)`，新增 action 需同步补 switch 分支，否则前端会收到 400。

**涉及文件**

- `h5/src/views/KtvKioskView.vue`（`adjustPitch` / `resetPitch` 两个 handler）
- `h5/src/components/ktv/KtvBottomDock.vue`（第 75、80 行按钮）
- `h5/src/components/ktv/KtvMediaSurface.vue`（第 91、92 行按钮）
- `h5/src/components/ktv/KtvModals.vue`（第 155-157 行按钮 + mixer 分区）
- `h5/src/stores/player.js`（`pitchSemitones` / `adjustPitch` / `setPitch` / `resetPitch`）
- `h5/src/player/webAudioChannelRouter.js`（若走 B1，需扩展音频图）
- `h5/src/api/client.js`（若走 B3，`makeControls` 增加 `setPitch`）
- `backend/src/main/java/com/homektv/web/ControlController.java`（若走 B3，新增 `case "pitch"`）

---

### P0-02 用户身份注册失败完全静默，点歌链路失效而用户无感知

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 点歌端全局（`App.vue` 启动流程），影响全部 12 条点歌路由 |
| **控件名称** | 非单一控件，属**应用启动期身份初始化流程**；触发控件为「全局搜索」「点歌」「收藏」「心愿反馈」等任一依赖 `client_token` 的操作 |

**问题描述**

应用启动时 `App.vue` 调用 `api.registerUser(user.clientToken, user.nickname)` 换取服务端身份。失败时执行：

```js
} catch (error) {
  user.markRegistrationFailure(error)
  // Still connect WebSocket for live room/TV monitoring even if registration needs retry
  player.connect()
}
```

`markRegistrationFailure()` 会把错误写入 `stores/user.js` 的 `registrationError`，并维护 `isReady` 状态——**但全项目没有任何组件读取 `registrationError` 或 `isReady`**。结果是：

- 界面**没有任何错误提示**，磁贴首页正常显示、可正常点击；
- `player.connect()` 仍然执行，WebSocket 连上后能收到队列快照，用户看到"歌曲列表是活的"，进一步降低警觉；
- 一旦执行点歌 / 收藏 / 提交心愿，服务端因 `client_token` 对应身份不存在而拒绝，前端表现为"点了没反应"或"加入失败"的泛化提示；
- 若为**网络抖动导致的瞬时失败**，`user.isRegistered` 判定逻辑（见 `App.vue:28`）会让用户长期停留在"半注册"状态，直到刷新页面重新注册。

这符合 P0 定义中的"登录流程中断"——虽然它不阻断页面渲染，但**阻断了用户参与点歌这一核心主流程，且无任何可感知信号**。

**根本原因**

`stores/user.js` 已经设计了完整的失败状态（`registrationError` / `isReady` / `markRegistrationFailure` / `markRegistrationSuccess`），但**状态设计完成后没有接入任何视图**。这是典型的"状态层完成、展示层遗漏"。

`App.vue` 的 catch 注释（"Still connect WebSocket for live room/TV monitoring"）说明这是有意保留降级行为，但降级后缺少"告知用户当前处于降级态"的配套动作。

**修改方案**

1. **在 `App.vue` 中显式消费失败状态**：catch 内除 `player.connect()` 外，增加一次用户可见提示：
   - 调用 `useToast().toast('身份初始化失败，部分功能不可用，点击重试')`，或
   - 在 `App.vue` 模板中增加一个条件渲染的 `banner`（`v-if="user.registrationError"`），文案含"重试"按钮。
2. **提供重试入口**：banner 的"重试"按钮重新执行 `api.registerUser(...)`，成功后 `markRegistrationSuccess()` 并刷新队列（`player.connect()` 或重新拉 `api.getQueue()`）。建议封装为 `user.retryRegistration()`，避免逻辑分散在 `App.vue` 与 `KtvModals.saveNickname` 两处。
3. **在依赖身份的写操作前做前置检查**：`orderSong` / `toggleFav` / `addWish` 等入口若 `!user.isReady`，直接提示"身份未就绪"并触发重试，而不是发出注定失败（或更糟：服务端 `insertIfAbsent` 隐式创建了一条空身份）的请求。
4. **可选但不建议省**：`api.registerUser` 增加 1 次自动重试（间隔 2s），覆盖瞬时网络抖动，再失败才进入降级态。

> 补充说明：后端 `queue/UserService.resolveUserId(clientToken)` 会 `insertIfAbsent` 隐式创建用户，因此"注册失败后仍能点歌"在某些路径下**可能成功**，但创建出的用户缺失昵称等元数据，且 `resolveUserId` 在 `clientToken` 为空时会抛错。这使问题表现为**间歇性**，进一步增加排查难度，是本条升为 P0 的第二个理由。

**涉及文件**

- `h5/src/App.vue`（`onMounted` 的 try/catch，第 33-45 行）
- `h5/src/stores/user.js`（`registrationError` / `isReady` / 新增 `retryRegistration`）
- `h5/src/components/ktv/KtvThreeColumnBrowser.vue`（点歌/收藏前置检查）
- `h5/src/components/ktv/KtvModals.vue`（心愿提交前置检查）
- `h5/src/composables/useToast.js`（复用现有提示机制）

---

## 四、P1 级问题

### P1-01 底部 Dock「原唱/伴唱」按钮仅视觉置灰，未真正禁用

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 点歌端（`KtvBottomDock`，底部常驻） |
| **控件名称** | 底部 Dock 圆形按钮「原唱/伴唱」（`KtvBottomDock.vue:11-20`） |

**问题描述**

当当前歌曲没有独立伴唱音轨时（`canVocal === false`），按钮只被加上 `class="disabled"`（CSS 降透明度），**没有加 `:disabled` 属性、也没有 `aria-disabled`**。用户仍可点击，点击后由父组件 `toggleVocal()` 二次拦截并弹 toast 说明"该歌曲无伴唱音轨"。

同类控件 `KtvMediaSurface` 全屏控台的原唱/伴唱按钮**正确使用了 `:disabled="!canVocal"`**，两处行为不一致。

影响：
- 交互不一致——同一功能在 Dock 与全屏态表现不同；
- 无障碍缺失——读屏软件会把该按钮播报为可用；键盘 Tab 仍可聚焦并触发；
- 用户多做一次无效点击才得到说明。

**根本原因**

`KtvBottomDock.vue:14` 用 `:class="{ disabled: !canVocal }"` 表达禁用，但 Vue 的 `class` 绑定只影响样式，不影响 DOM 的 `disabled` 属性。这是把"视觉态"当成了"交互态"。

**修改方案**

将第 11-20 行的按钮改为：

```vue
<button
  type="button"
  class="dock-circle"
  :class="{ active: player.vocalMode === 'accompaniment' }"
  :disabled="!canVocal"
  :aria-disabled="!canVocal"
  :aria-label="player.vocalMode === 'original' ? '当前原唱，点击切换伴唱' : '当前伴唱，点击切换原唱'"
  @click="$emit('toggle-vocal')"
>
```

同时确认 `.dock-circle:disabled` 的 CSS 已有置灰与 `cursor: not-allowed`，与现有 `.disabled` class 视觉保持一致（可直接把 `.disabled` 样式规则改为 `.dock-circle:disabled`，避免两套样式并存）。

**涉及文件**

- `h5/src/components/ktv/KtvBottomDock.vue`（模板第 11-20 行，样式 `.disabled` 规则）

---

### P1-02 房主（Host）状态拉取失败被静默吞掉，导致房主按钮状态与实际不符

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 点歌端「已点」抽屉（`/queue` 路由，`KtvQueueDrawer`） |
| **控件名称** | 抽屉顶部「认领房主 / 释放房主」按钮组 |

**问题描述**

`KtvQueueDrawer.vue` 有两处拉取房主状态：

```js
// 第 136 行：抽屉打开时
watch(() => props.open, (isOpen) => {
  if (isOpen) {
    api.roomHostStatus(user.clientToken).then(mergeHost).catch(() => {})
    loadHistory()
  }
})

// 第 143 行：组件挂载时
onMounted(() => {
  if (user.clientToken) {
    api.roomHostStatus(user.clientToken).then(mergeHost).catch(() => {})
  }
})
```

两处 `catch(() => {})` 都是空块。失败后 `player.roomHost` 保持旧值（可能是"我是房主"），但服务端实际状态可能已经变了（例如另一位用户在上一次会话中认领、或房主 token 已过期）。

**具体后果**：用户看到「释放房主」按钮（因为本地认为自己是房主），点击后服务端返回"你不是房主"，或反过来——用户看到「认领房主」，点击后收到"已被他人认领"。两种都是**状态与实际不符引发的操作失败**。

对比同文件其他写操作（切歌、顶歌、删除、重试）均有 `try/catch + toast`，唯独状态同步是空的，说明是遗漏而非有意设计。

**根本原因**

房主状态被当成"尽力而为的装饰信息"处理，但它实际决定了两个写操作的可用性。`mergeHost` 只在成功路径被调用，失败路径没有回退策略（例如"未知态"）。

**修改方案**

1. 引入三态：`hostState = 'unknown' | 'me' | 'other'`。请求失败时置 `'unknown'`，UI 上房主按钮组显示为"状态获取失败，点击重试"，而不是沿用旧值。
2. 失败时给一次可见反馈（`toast('房主状态获取失败，请重试')`），并提供重试按钮。
3. 若判定为网络瞬时失败，可做 1 次延迟 2s 的重试，仍失败才置 `unknown`。
4. 写操作（认领/释放）前若 `hostState === 'unknown'`，先重新拉取状态再执行，避免基于过期状态操作。

**涉及文件**

- `h5/src/components/ktv/KtvQueueDrawer.vue`（第 136、143 行，以及房主按钮的渲染条件）
- `h5/src/stores/player.js`（`roomHost` 状态与 `mergeHost` 的落点）

---

### P1-03 收藏数据预加载失败被静默吞掉，收藏态显示错误

| 字段 | 内容 |
| --- | --- |
| **所属页面** | ① 应用启动期（`App.vue`）② 点歌端「我的收藏」Tab（`/favorites`，`KtvThreeColumnBrowser`） |
| **控件名称** | 歌曲卡片上的「♡ 收藏」按钮（依赖 `favorites.has(song.id)` 渲染实心/空心） |

**问题描述**

两处调用均为空 catch：

```js
// App.vue:40 —— 启动期预加载
favorites.load(user.clientToken).catch(() => {})

// KtvThreeColumnBrowser.vue:1055 —— 进入收藏 Tab 时全量拉取
const res = await loadAllFavoritePages((page, size) => api.favorites(user.clientToken, page, size, { signal: req.signal }))
...
favorites.load(user.clientToken, true).catch(() => {})
```

失败后 `favorites` store 保持空集合，后果是**所有歌曲卡片都显示"未收藏"**——包括用户实际已经收藏的歌曲。用户点一次收藏，实际触发的是"新增收藏"；再点一次变成"取消收藏"，但第一次点击在服务端并未产生用户预期的"取消"效果（因为本来就已收藏，新增请求可能被幂等处理或产生重复）。

对「我的收藏」Tab 来说更直接：拉取失败 → 列表为空 → 用户看到"你还没有收藏任何歌曲"，而实际上收藏数据完好。这是**数据未丢失但呈现为丢失**的误导性故障。

**根本原因**

收藏态被认为是"局部装饰信息"，失败时选择了静默降级为空集合。但空集合在业务语义上等价于"没有任何收藏"，是一个**有含义的确定性状态**，不能用作失败的默认值。

**修改方案**

1. `favorites` store 增加 `loadState: 'idle' | 'loading' | 'ready' | 'error'`。失败时置 `error`，**不要**用空数组冒充 `ready`。
2. `KtvThreeColumnBrowser` 在 `activeTab === 'favorites'` 且 `loadState === 'error'` 时，渲染明确的错误态与「重试」按钮，而不是空列表文案。
3. 歌曲卡片的收藏按钮在 `loadState !== 'ready'` 时，应显示为中性态（不是"未收藏"），避免误导用户点击。
4. `App.vue` 的启动预加载失败可以保持静默（用户还没进入收藏场景），但必须在进入收藏 Tab 时重新拉取并处理失败——即第 2 点的兜底能覆盖启动期失败。

**涉及文件**

- `h5/src/App.vue`（第 40 行）
- `h5/src/stores/favorites.js`（新增 `loadState`）
- `h5/src/components/ktv/KtvThreeColumnBrowser.vue`（第 1055 行、`favorites` Tab 的渲染分支第 576 行附近）

---

### P1-04 音频解锁（Autoplay Unlock）失败无任何提示，用户卡在"无声播放"

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 点歌端全屏演唱态（`KtvMediaSurface`） |
| **控件名称** | 「点击开启声音」解锁按钮（`class="autoplay-unlock"`，第 123-124 行） |

**问题描述**

浏览器的自动播放策略（Autoplay Policy）会阻止未静音的媒体自动播放，前端通过 `player.audioBlockedByAutoplay` 展示一个"点击开启声音"的覆盖按钮。该状态设置逻辑**正确**（`attemptPlay()` 的 `NotAllowedError` 分支会置位并做静音重试）。

问题在解锁按钮的处理函数：

```js
async function unlockAudio() {
  player.audioBlockedByAutoplay = false
  const el = videoEl.value
  if (!el) return
  el.muted = player.muted
  syncAudioRouter()
  await channelRouter.resume()
  if (player.isPlaying) {
    el.play().catch(() => {})   // ← 静默
  }
}
```

`el.play()` 失败时（例如音频输出设备被切换、浏览器仍判定手势不足、媒体源已失效）**没有任何提示**，且 `audioBlockedByAutoplay` 已被置为 `false`——覆盖按钮消失，用户看到视频在走但**完全没有声音**，也没有任何入口再触发一次解锁。

这是"用户可见控件执行了无效操作且不告知"的典型场景，位于演唱主流程上。

**根本原因**

解锁流程把"清除阻断标志"放在了实际播放成功之前，成功与否都不回滚标志位；同时 `play()` 的 rejection 被完全忽略。

**修改方案**

1. 改为"先验证再清标志"：

```js
async function unlockAudio() {
  const el = videoEl.value
  if (!el) return
  el.muted = player.muted
  syncAudioRouter()
  try {
    await channelRouter.resume()
    if (player.isPlaying) await el.play()
    player.audioBlockedByAutoplay = false   // 仅成功后清除
  } catch (e) {
    player.audioBlockedByAutoplay = true    // 失败回滚，覆盖按钮重新出现
    toast('开启声音失败，请再点一次或检查设备输出')
  }
}
```

2. 若 `play()` 抛 `NotAllowedError`，说明仍是手势问题，提示文案应引导"再点一次"，不要提示"检查设备"。
3. 建议同时把失败原因上报到 `player.reportPlayError`（若已有该通道），便于后台诊断。

**涉及文件**

- `h5/src/components/ktv/KtvMediaSurface.vue`（`unlockAudio()`，第 335-348 行；模板第 120-126 行）

---

### P1-05 歌手头像刮削进度轮询失败被静默吞掉，进度条永久停滞

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 后台「歌手库」（`/admin/artists`） |
| **控件名称** | 头像刮削进度显示区（依赖 `avatarProgress`） |

**问题描述**

```js
async function refreshAvatarProgress() {
  if (!api.adminArtistAvatarProgress) return
  try {
    avatarProgress.value = await api.adminArtistAvatarProgress()
  } catch (_) {}
}
```

失败时既不清空进度、也不标记失败，`avatarProgress` 保留上一次的值。用户看到的是一块**静止不动的进度条**（例如永远停在 12/50），而实际上后台任务可能已经完成或已经失败。

与同视图其他操作（批量分析、头像重刮等均有错误提示）形成明显反差。

**根本原因**

进度信息被当作纯装饰，失败不做处理；缺少"进度不可用"的显式态。

**修改方案**

1. 失败时置 `avatarProgressState = 'error'`，UI 显示"进度获取失败 · 重试"，而不是保留陈旧数字。
2. 若连续 N 次（建议 3 次）失败，停止轮询并提示"进度同步已中断"，避免无意义请求。
3. 重新拉取成功后恢复 `'ready'` 并继续轮询。

**涉及文件**

- `h5/src/views/admin/ArtistLibraryView.vue`（`refreshAvatarProgress()`，第 109-116 行；进度渲染处）

---

### P1-06 六组后端能力在网页端零入口（功能不可达）

| 字段 | 内容 |
| --- | --- |
| **所属页面** | 后台（`/admin/*`）—— 应有入口但当前**不存在** |
| **控件名称** | 缺失，非现有控件缺陷 |

**问题描述**

将后端 134 个端点与前端 99 条调用做双向集合比对后，发现以下端点**后端已完整实现、前端零调用**（已排除 `<img src>` / `<video src>` 直接引用的资源端点，以及 TV 客户端专用端点）：

| 后端端点 | 能力 | 推断用途 | 级别 |
| --- | --- | --- | --- |
| `POST /api/admin/iktv-reference/fusion/resume` | IKTV 引用融合：恢复批次 | 曲库治理 | P1 |
| `GET /api/admin/iktv-reference/fusion/status` | IKTV 引用融合：查询状态 | 曲库治理 | P1 |
| `GET /api/admin/iktv-reference/fusion/batches/{batchId}` | IKTV 引用融合：批次详情 | 曲库治理 | P1 |
| `POST /api/admin/iktv-reference/fusion/batches/{batchId}/rollback` | IKTV 引用融合：批次回滚 | 曲库治理 | P1 |
| `GET /api/admin/imports` | 导入记录列表 | 数据治理 | P1 |
| `GET /api/admin/imports/{id}/source` | 导入源文件详情 | 数据治理 | P1 |
| `GET /api/admin/vocal-review` | 人声（原唱/伴唱）复核队列 | 曲库治理 | P1 |
| `POST /api/admin/artists/gender-dictionary/import` | 性别词典导入（仅 `apply` 有入口） | 歌手治理 | P2 |
| `GET /api/admin/scan/rebind`（或 POST） | 扫描重绑定 | 运维 | P2 |
| `POST /api/admin/files/{fileId}/vocal-track` | 单文件音轨设置（`audio-layout` 有入口） | 曲库治理 | P2 |

其中 **IKTV 引用融合的 4 个端点**最值得注意：它带 `resume` / `rollback`（批次恢复与回滚），是**有状态、有副作用的运维能力**。如果这类能力只靠 curl 调用，一旦线上批量操作出问题，网页端既无法查看批次状态、也无法回滚，运维闭环是断的。

**根本原因**

后端先行实现了 IKTV 融合、导入记录、人声复核等治理能力（部分有对应单元测试），但网页端 UI 未跟进。两侧能力矩阵没有做对账。

**修改方案**

按"是否需要日常运维"分两档处理：

1. **必须在网页端补齐（P1）**：IKTV 融合的 status / batches / resume / rollback 四项。建议在「系统设置」或独立「引用治理」页增加一个分区，最小可用形态只需：当前批次状态卡片 + 恢复按钮 + 回滚按钮（回滚需二次确认）。
2. **可暂缓（P2）**：导入记录、人声复核、性别词典导入、扫描重绑。若确定短期不提供 UI，应在后端接口上标注 `@Deprecated` 或在 API 文档中标注"仅 CLI/运维使用"，避免后续误判为遗漏。
3. **不建议**做的处理：仅为了"消除零入口"而加一批低价值页面。对确实不需要 UI 的端点，明确标记为"内部运维接口"比补 UI 更合理。

**涉及文件**

- `backend/src/main/java/com/homektv/web/IktvReferenceFusionAdminController.java`（4 个端点）
- `backend/src/main/java/com/homektv/web/`（`imports` / `vocal-review` / `gender-dictionary` / `scan/rebind` 所属 Controller）
- `h5/src/api/client.js`（补齐对应 API 封装）
- `h5/src/views/admin/`（新增治理视图或在 `SettingsView.vue` 增加分区）
- `h5/src/router/index.js`（如新增独立页面需注册路由）

---

## 五、P2 级问题

### P2-01 `useAsyncResource.js` 为死代码

**所属页面**：无（未被任何页面引用）
**问题描述**：`h5/src/composables/useAsyncResource.js` 实现了 `{ data, loading, error, run, reset }` 的通用异步资源封装，但全项目**零引用**（`grep -rn "useAsyncResource"` 仅命中自身与自身测试）。存在配套测试 `useAsyncResource.test.js`，使覆盖率指标虚高。
**根本原因**：先建基础设施、后未采用；测试先行但业务未接入。
**修改方案**：二选一——① 删除该文件与对应测试；② 若确有统一异步态的规划，在下一个迭代把 `ArtistLibraryView.refreshAvatarProgress`、`SettingsView` 的分区加载等手写 `loading/error` 场景迁移过来，让基础设施真正生效。**不建议**长期保留无引用的 composable。
**涉及文件**：`h5/src/composables/useAsyncResource.js`、`h5/src/composables/useAsyncResource.test.js`

---

### P2-02 pitch 相关 store 方法与 API 封装成为孤立代码

**所属页面**：`stores/player.js`
**问题描述**：`pitchSemitones` / `adjustPitch` / `setPitch` / `resetPitch` 四个成员**无任何调用方**（若按 P0-01 的方案 A 隐藏入口，这一状态会持续）。
**根本原因**：同 P0-01——能力未实现，状态先落地。
**修改方案**：与 P0-01 联动处理。若选择隐藏入口，应**保留**这些方法（它们是方案 B1/B3 的接入点，且已有 `clampPitch` 边界处理），但在文件内加注释标明"待接入音频图，当前无调用方"，避免后续维护者误以为是活跃代码；若最终确认不做升降调，则连同 UI 一并删除。
**涉及文件**：`h5/src/stores/player.js`（第 53、209、320-332 行）

---

### P2-03 `api/client.js` 中 5 个 API 方法无调用方

**所属页面**：`h5/src/api/client.js`
**问题描述**：以下封装未被任何组件调用：`songDetail`、`history`（非 `recentHistory`）、`adminDiagnostics`、`adminResetMusicSourceCooldowns`、`adminAiCreateTask`（单条，前端只用 `batch` 版本）。
**根本原因**：API 层按后端能力全量铺开，前端按需取用，未做去重。
**修改方案**：逐个判断——`songDetail` / `history` 若近期无"歌曲详情页 / 全部历史"规划则删除；`adminDiagnostics` 已有 `adminDiagnosticBundle` 覆盖，可删；`adminResetMusicSourceCooldowns` 对应的后端能力在设置页可能有用（当前 `testMusic` 有入口、`resetCooldown` 无入口），建议**补 UI 入口**而非删除；`adminAiCreateTask` 保留（与 batch 共用后端逻辑，删除会增加后续单条创建的改造成本）。
**涉及文件**：`h5/src/api/client.js`

---

### P2-04 昵称修改失败导致本地状态与服务端不一致

**所属页面**：点歌端「系统设置」弹窗（`KtvModals` settings 分区）
**问题描述**：

```js
user.register(next)                              // ← 先改本地
const res = await api.registerUser(user.clientToken, user.nickname)
if (!user.markRegistrationSuccess(res)) throw new Error('昵称更新失败')
```

`user.register(next)` 在发起请求**之前**就把本地昵称改掉了。若后续 API 失败，`catch` 只弹 toast，本地 `user.nickname` 仍是新值，而服务端仍是旧值。用户刷新页面前会看到"界面显示新昵称、但别的设备/后台看到旧昵称"。
**根本原因**：乐观更新未配回滚。
**修改方案**：保存失败时回滚到旧昵称（在 `user.register(next)` 之前记录 `const prev = user.nickname`，失败分支执行 `user.register(prev)`），或改为"先请求成功再改本地"。
**涉及文件**：`h5/src/components/ktv/KtvModals.vue`（`saveNickname()`，第 281-297 行）、`h5/src/stores/user.js`

---

### P2-05 SPA 深链接兜底最多 3 段，超出即 404

**所属页面**：全部（后端 `SpaController`）
**问题描述**：`SpaController` 为前端路由提供深链接兜底，模式为 `/m/{p}`、`/m/{p}/{s}`、`/m/{p}/{s}/{t}`，**最多 3 段**。当前 21 条路由最深为 `/admin/metadata-scrape`（2 段）、`/admin/ai`（2 段），全部覆盖。
**根本原因**：兜底按现有路由段数硬编码。
**修改方案**：短期无需动作，但应在 `SpaController` 注释中记录该约束；若未来新增 4 段路由（如 `/admin/ai/playlists/{id}`），必须同步扩展兜底模式，否则出现"SPA 内导航正常、直接刷新 404"的隐蔽故障。
**涉及文件**：`backend/src/main/java/com/homektv/web/SpaController.java`

---

### P2-06 部分只读端点无前端引用（需确认保留意图）

**所属页面**：无
**问题描述**：`/api/build-info`、`/api/standby/logo`（GET）、`/api/ready`、`/api/library/status`、`/api/library/scan`、`/api/playback/resolve/{fileId}` 前端零引用。
**根本原因**：其中多数为 **TV 客户端（Android）专用**或运维健康检查端点（`/api/ready` 常被容器探针调用），网页端本就不应调用。
**修改方案**：**无需修改代码**，但建议在 API 文档中明确标注"TV 端 / 运维专用"，避免下一次审计重复判定。
**涉及文件**：无（文档层面）

---

## 六、已核查并排除的疑似问题

以下 14 项在初审时被标记为疑似缺陷，经源码与后端交叉核对后**确认实现正确**，记录在此以避免后续重复排查。

| # | 疑似问题 | 核查结论 |
| --- | --- | --- |
| 1 | `/api/qr` 是否为占位图 | ✅ 后端 `QrController` 用 zxing **真实生成 PNG**，`size` 钳制 `[120,1080]`，30s 缓存；前端 `KtvModals.vue:268` 真实引用 |
| 2 | 显示地址 `display_address` 是否双向打通 | ✅ `settingsState.js` 的 `EDITABLE_GENERAL_KEYS` 含 `display_address`，`canonicalizeSettings` 把旧 `qr_address` 迁移过来；后端 `ServerAddressService.manualAddress()` 正确读取并回退 |
| 3 | 待机 logo 路径是否可被越权写入 | ✅ `standby_logo_path` 属 `SettingService.INTERNAL_KEYS`，**不在** `EDITABLE_KEYS` 白名单内，只能通过上传接口写；前端 `EDITABLE_GENERAL_KEYS` 同样不含 |
| 4 | NAS 只读红线（`EXTERNAL_READ_ONLY`）是否只在前端隐藏 | ✅ 服务端 `LibraryModePolicy.requireManaged` **硬阻断**：`MediaImportService.deleteSources/deleteSourcesByFilter/deleteSource/prioritizeTranscode`、`AdminService.deleteSong/deleteSongFile` 共 6 处；前端隐藏只是第二层 |
| 5 | 设置写入是否有越权/脏数据风险 | ✅ `SettingService.putEditable` 有 `EDITABLE_KEYS` 白名单（非法键抛 `SETTING_NOT_ALLOWED`）+ 逐键 `validateKeyValue` 类型校验 + `@Transactional` + 提交后刷新缓存 |
| 6 | 心愿单是否有长度/空值校验 | ✅ `WishController` 校验空值与 100 字符上限；CSV 导出带 UTF-8 BOM 与 `Content-Disposition` |
| 7 | `/admin/songs` 是否死路由 | ✅ 为 `redirect` 到 `admin-ktv-library`，行为正确 |
| 8 | 后台未登录直接访问 `/admin/*` 是否泄露数据 | ✅ `router.beforeEach` 校验 `meta.admin` + `useAdminAuthStore().isAuthenticated`；后端 `AdminAuthWebConfig` 拦截 `/api/admin/**`（仅放行 `/api/admin/auth/**`），双保险 |
| 9 | 登录后 `redirect` 参数是否可被开放重定向利用 | ✅ `AdminLoginView.redirectPath()` 做 `/admin` 前缀白名单校验 |
| 10 | 路由切换后浏览器组件是否不刷新 | ✅ `KtvThreeColumnBrowser.vue:1254-1283` 对 7 个 `initial*` prop 有 `watch`，回调内重写本地状态并 `reloadActive()` |
| 11 | 自动播放被拦截时是否有降级 UI | ✅ `attemptPlay()` 的 `NotAllowedError` 分支置 `audioBlockedByAutoplay=true` 并静音重试，模板渲染 `.autoplay-unlock` 覆盖按钮（其**解锁失败**的处理缺陷另立 P1-04） |
| 12 | 弹窗可访问性是否达标 | ✅ `useDialog` + `DialogHost`：Teleport、`setBackgroundInert`、焦点陷阱、Escape 关闭、关闭后焦点归还；全站无 `window.confirm` |
| 13 | 中文软键盘 T9 多击是否可用 | ✅ `KtvSoftKeyboard` 全拼 26 键 + T9 九宫格 800ms multi-tap、退格、清空、搜索逻辑完整 |
| 14 | WebSocket 断线是否会话错乱 | ✅ `api/ws.js` 指数退避 `[1000,2000,5000,10000]`、25s ping、`SnapshotChunkAssembler` 严格校验分片一致性并拒绝重复分片；`player.applySnapshot` 用 `stateRevision` 拒旧快照 |

**此外，检查点 ⑦ 的专项结果：**

- 全量搜索 `TODO|FIXME|HACK|暂未实现|待实现|未实现|mockData`，在 **19 个交互组件与全部 JS 模块中零命中**（命中项均为 `input` 的 `placeholder` 属性或测试文件）。**代码库中无 TODO 注释残留，无 Mock 数据源。**
- 唯一的"空实现"是 P0-01 的升降调链路。

**检查点 ⑧ 的专项结果：**

- 前端 99 条 API 调用 ↔ 后端 134 个端点做模板化匹配（容忍 `{id}` 路径参数与前缀），**未匹配数为 0**，即**不存在调用不存在端点的死链接**。
- 21 条路由全部在 `router/index.js` 中注册，全部有可渲染组件，全部有可达入口（磁贴、Tab、侧栏、HeaderBar、`router-link`）。

---

## 七、修复批次建议

| 批次 | 内容 | 建议理由 |
| --- | --- | --- |
| **第 1 批（立即）** | P0-01 第 1 步（隐藏 7 个升降调入口）、P0-02（注册失败提示 + 重试）、P1-01（`:disabled`） | 全是**改动小、风险低、见效直接**的止血项，不涉及架构变更。P0-01 第 1 步只是加一个 prop/`v-if` |
| **第 2 批（本迭代）** | P1-02、P1-03、P1-04、P1-05 | 统一处理"静默失败"这一模式问题：引入错误态 + 重试，是一个可在 4 个文件内并行推进的横切改造 |
| **第 3 批（下一迭代）** | P1-06（IKTV 融合 4 端点的 UI 入口） | 需要新页面或新分区 + 路由注册，工作量独立；且涉及**有副作用的回滚操作**，需配套二次确认设计 |
| **第 4 批（技术债）** | P2-01 ~ P2-06 | 清理死代码、对齐状态一致性、记录架构约束 |
| **立项评估** | P0-01 第 2 步（升降调真实实现） | 三选一路径（B1 Web Audio / B2 服务端转码 / B3 TV 透传）需先确认产品优先级与 TV 端能力，不应混在审计整改里仓促上马 |

---

## 八、附录：逐检查点覆盖矩阵

| 检查点 | 覆盖方式 | 结论 |
| --- | --- | --- |
| ① 连接业务逻辑 | 309 处事件绑定全量提取 handler 名并与 `<script>` 定义比对 | ✅ 无悬空绑定 |
| ② 真正执行功能 | 逐 handler 读实现，排除"仅 toast / 仅赋值本地 ref" | ❌ P0-01（7 个按钮仅 toast） |
| ③ 操作数据库 | 追至后端 Service 的 `save/delete/update` 与 `@Transactional` | ✅ 均有事务与校验；无绕过 `requireManaged` 的写路径 |
| ④ 更新 UI | 检查 API 返回后是否回写状态 / 重新拉列表 | ✅ 主要列表页均有刷新；⚠️ P1-03 / P1-05 的失败态未反映 |
| ⑤ 异常处理 | 全量搜索空 catch 块 | ⚠️ 6 处静默失败（P1-02/03/04/05） |
| ⑥ 数据校验 | 前端非空/范围 + 后端白名单/类型 | ✅ 关键写路径齐全（昵称、心愿、设置、歌单、元数据） |
| ⑦ TODO/Mock/空实现 | 关键词全量搜索 + 空实现人工核对 | ⚠️ 无 TODO/Mock；1 处空实现（P0-01） |
| ⑧ 死链接/未注册导航 | 路由表 ↔ `router-link` ↔ 后端端点三方比对 | ✅ 21 条路由全通；99 条 API 调用 100% 命中后端 |
| ⑨ 点击无响应/功能缺失/流程中断 | 结合 ② 与"后端有端点但前端零入口"的差集 | ❌ P0-01（功能缺失）、P0-02（流程中断）、P1-06（功能不可达） |

---

*本报告仅记录审计结论与整改建议，未对代码库做任何修改。*
