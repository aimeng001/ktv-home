# 网页端功能缺陷：对抗性审查结论与 TDD 整改计划（v2 · 影响面修订版）

| 项 | 内容 |
| --- | --- |
| 审查对象 | `docs/audits/web-feature-delivery-audit-2026-11-20.md`（原审计报告）的全部 14 项结论 |
| 审查方式 | **以推翻为目标**：反向检索补偿逻辑、全局状态消费方、后端自动修复路径、既有测试断言 |
| 本版重点 | 在 v1 基础上补做**影响面分析**与**回归风险排查**，修正 v1 中 6 处会引入新缺陷的设计 |
| 已确认决策 | **R-06（IKTV 引用融合）由网页端承载**（用户 2026-11-20 拍板） |
| 本文档性质 | 整改计划（TDD）。**尚未修改任何源码** |
| 复审时间 | 2026-11-20 |

---

## 0. v2 相对 v1 的修订清单

v1 给出的方案在**局部正确、整体有坑**。本轮逐项做影响面核验，发现 v1 有 6 处会引入新问题，另有 4 处遗漏的影响面。修订如下：

| # | v1 的做法 | v2 修正 | 若按 v1 实施会产生什么 |
| --- | --- | --- | --- |
| M1 | R-03 横幅加在 `App.vue` | 改加在 `KtvKioskView.vue` 内部 | `App.vue` 无样式层，`KtvKioskView` 是 `height:100vh; overflow:hidden`，外部加元素**必然产生滚动条、破坏全屏**；且后台 9 个页面也会显示点歌人的身份错误 |
| M2 | R-01 error 态下**不渲染**认领/释放按钮 | error 态下**保留**按钮，只额外显示错误行 + 重试 | 形成死锁：取不到状态 → 隐藏认领按钮 → 无法认领 → 状态永远取不到 |
| M3 | R-02 失败时只保持 `audioBlockedByAutoplay=true` | 同时把 `el.muted` 恢复为 `true` | UI 显示静音图标、元素实际非静音，状态二次不一致 |
| M4 | R-05 只加 `:disabled` 改 CSS | 同时补 `title` + `aria-label` 说明原因 | `toggleVocal()` 里"当前曲目无伴唱音轨"的 toast 永远走不到，用户不知道按钮为何变灰 |
| M5 | R-09"若有 pitch 测试则删" | **确认存在** `player.test.js:430` 用例，必须删 | 删代码不删测试 → `npm test` 直接红 |
| M6 | R-06 只写"加 UI + 4 个接口" | 补：rollback 会 `UPDATE songs` + 重建 `iktv_song_categories`，**影响点歌端分类 Tab**；必须展示冲突计数 | 运维点回滚后，点歌端"高清专区/抖音神曲/综艺"Tab 数据变化却无人验收，出问题找不到原因 |

新增影响面（v1 完全未考虑）：**I-01 ~ I-07**，见第二章。

---

## 一、审查结论：原报告哪些结论站不住

### 1.1 被完全推翻的结论

#### ❌ P0-01「升降调全链路空实现」——不成立

**反证（决定性）：这是显式声明的产品能力边界，不是遗漏。**

1. **`KtvKioskView.vue:396-406` 的 toast 是产品声明，不是占位符：**

```js
function adjustPitch() {
  toast(player.isTvConsole
    ? 'TV 端实时变调请在电视端调音菜单调节'
    : '网页浏览器端暂不支持无变速实时升降调，请保持原调或切至 TV 端调节')
}
function resetPitch() {
  toast(player.isTvConsole
    ? '请在电视端调音菜单恢复原调'
    : '当前网页端默认保持原调播放')
}
```

2. **`KtvModals.vue:159-161` 调音台弹窗内常驻说明文字**，与 toast 同义。
3. **`makeControls()` 无 pitch 方法**（只有 `setVolume` / `setVocal` / `swapVocalTracks`），后端 `ControlController` 也无 pitch 分支——两侧一致地"未提供"。

**原报告错在哪**：把"点击后只有 toast"直接等同于空实现。空实现的定义是"无业务效果且无告知"，而这里是"无业务效果但**有准确告知 + 替代路径指引**"——这是降级设计（graceful degradation）。

**残留真实问题（R-09，P2）**：`stores/player.js` 的 `pitchSemitones` / `adjustPitch` / `setPitch` / `resetPitch` 是纯死代码（全项目零调用，已 grep 验证）。它正是误导原报告的原因。

---

#### ❌ P0-02「注册失败导致点歌链路失效」——严重夸大，降为 P1

**反证（决定性）：后端会兜底创建用户。**

`queue/UserService.java:25-32`：

```java
public Long resolveUserId(String clientToken) {
    if (clientToken == null || clientToken.isBlank()) return null;
    validateClientToken(clientToken);
    userRepo.insertIfAbsent(clientToken, defaultNickname());   // ← 自动补建
    return userRepo.findByClientToken(clientToken)...getId();
}
```

`validateClientToken`（同文件 63-70 行）只校验非空、长度上限、无控制字符——本地 `genToken()` 生成的 UUID 必然通过。**注册失败后点歌仍然成功**，只是昵称为默认值。

**但这条不撤销，只降级——因为真实危害被原报告漏掉了：**

`player.js:171-176` 的房主判定依赖 `serverUserId`：

```js
const isHost = Boolean(payload?.claimed
  && Number(payload?.hostUserId) > 0
  && Number(payload.hostUserId) === Number(user?.serverUserId))
```

`markRegistrationFailure()` 会把 `serverUserId` 置 `null`，`Number(null) === 0` 永远不等于真实 `hostUserId`。后果：认领房主后**永远显示不是房主**，`v-if="isMine(item) || host.isHost"` 令所有他人歌曲的顶歌/删除按钮消失，再点认领返回"已被认领"。

### 1.2 被降级的结论

#### ⚠️ P1-03「收藏态全错」→ P2

`KtvThreeColumnBrowser.vue:1046-1063` 的 `loadFavorites()` **有独立 try/catch + `loadError` 提示**，失败时渲染"收藏读取失败"。`favorites.load()` 只同步心形图标，且 `stores/favorites.js:41-48` 失败时**不更新 `loadedFor`**，下次自动重试。

#### ⚠️ P1-05「进度永久停滞」→ P2

`refreshAvatarProgress()` 仅在 `onMounted` 与 3 个操作完成回调中调用，**没有 `setInterval`**，不存在"永久停滞"。

#### ⚠️ 「6 处静默失败」→ 实际只有 3 处 P1

| 位置 | 复审 | 理由 |
| --- | --- | --- |
| `KtvQueueDrawer.vue:136,143` | **P1 成立** | HTTP 是房主状态唯一初始来源 |
| `KtvMediaSurface.vue:343` | **P1 成立** | 标志位已清、无提示 |
| `App.vue:40` | P2 | 失败不缓存、下次自动重试；仅影响图标 |
| `KtvThreeColumnBrowser.vue:1055` | P2 | 列表有独立错误处理 |
| `ArtistLibraryView.vue:113` | P2 | 非轮询、下次操作即刷新 |

### 1.3 强化 P1-02 的新证据

1. **HTTP 是唯一初始来源。** `ROOM_HOST_CHANGED` 只在 `RoomHostController.java:84` 广播（认领/释放时）。WS 连接建立时**不推送**，`QueueSnapshot` 结构无 roomHost 字段（`queue/` 与 `ws/` 目录 grep 确认）。
2. **失败后果是"管理能力整体不可见"**：`KtvQueueDrawer.vue:61` 的 `v-if="isMine(item) || host.isHost"` 控制每首歌的顶歌/删除按钮。
3. **同文件已有正确范式**：历史 Tab 在 `historyStatus === 'error'` 时渲染"读取失败 + 重试"（64-70 行）。房主状态是同类数据、同一文件、漏做错误态。

### 1.4 原报告的事实错误（已勘误）

| # | 原报告 | 实际 |
| --- | --- | --- |
| 1 | `GET /api/admin/scan/rebind` 存在但无入口 | **不存在**。`rebind` 仅为 `LibraryScanCoordinator.rebindCurrentRoot()` 内部方法（95/112/139 行），无任何 `@*Mapping` |
| 2 | `GET /api/admin/imports/{id}/source`（"查看详情"） | 实为 **`@DeleteMapping`**（`AdminScanController.java:281`），语义是删除源文件记录 |

根因：端点提取脚本按"类级 + 方法级"拼接，未逐条回源核对 Controller。

### 1.5 原报告漏报的问题

#### 🆕 N-01 `user.register()` 会清空可写身份

`stores/user.js:register()` 会置 `serverUserId = null`、清 `registrationError`、`removeItem(USER_ID_KEY)`。而 `KtvModals.saveNickname()` 先调 `register(next)` 再发请求，失败后用户从 `isReady=true` 掉成**没有错误信息的未就绪态**，房主判定永久失效直到刷新。

---

## 二、本轮新增：影响面与回归风险核验

以下是 v1 未做、本轮补做的核验结果。每条都附**源码证据**与**处置方式**。

### I-01 R-03 横幅不能放在 `App.vue`（会导致全屏布局破坏 + 后台页污染）

**证据**：

- `App.vue` 模板只有 `<router-view>` + `<ToastHost>` + `<DialogHost>`，**没有 `<style>` 块**。
- `KtvKioskView.vue:476-479`：

```css
.ktv-kiosk-shell {
  height: 100vh;
  width: 100vw;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
```

**影响**：若在 `App.vue` 的 `<router-view>` 前插入 banner，页面结构变成 `banner(40px) + .ktv-kiosk-shell(100vh)` → 总高超出视口 → 出现滚动条、底部 Dock 被推出屏幕。

**第二个问题**：`App.vue` 是全局的，`/admin/*` 9 个页面与 `/admin/login` 也会显示"身份初始化失败"——但那是**点歌人身份**，与管理员无关，属于误导。

**处置**：横幅改放 `KtvKioskView.vue` 内部，位置在 `<KtvHeaderBar>` 之后、`<main class="kiosk-stage">` 之前。因为 shell 是 `flex column` + `overflow:hidden`，banner 作为 flex item 会**自动压缩 `kiosk-stage`（flex:1）**，不会溢出。同时天然只作用于 12 条点歌路由。

---

### I-02 R-01 若在 error 态隐藏认领按钮，会造成"永远无法认领房主"死锁

**证据链**：

1. `RoomHostController:84` 只在认领/释放时广播 `ROOM_HOST_CHANGED`，**连接时不推送**
2. WS 静默时，`player.roomHost` 保持初始值 `{claimed:false, isHost:false}`（`player.js:43`）
3. v1 方案"error 时不渲染认领/释放按钮" → 用户看不到"认领房主"
4. 用户无法认领 → 服务端无状态变更 → **永远不会广播** → 状态永远取不到

**处置**：error 态**保留**按钮（按 `host.claimed` 正常渲染，默认 false → 显示"认领"），只**额外**插入一行错误提示 + 重试按钮。认领动作本身有 `try/catch + toast`（`claimHost` 177-182 行），失败会告知。

**补充联动**：`mergeHost()` 内需在收到有效数据后置 `hostStatus = 'ready'`。因为存在 `watch(() => player.roomHost, value => mergeHost(value), { deep: true })`（第 129 行），WS 推送会走 `mergeHost` → 自动恢复并消除错误行。

---

### I-03 R-02 修复后标志位可能永久卡 `true`，静音图标常亮

**证据**：

- `KtvMediaSurface.vue:217`：`effectiveMuted = !isWebHost || player.muted || player.audioBlockedByAutoplay`
- `KtvMediaSurface.vue:322-330`（`attemptPlay`）：`NotAllowedError` 时置 `true` **并** `el.muted = true`
- `toggleMute()`（`KtvKioskView.vue:388-394`）走 `controls.setMuted()` 改的是**服务端** muted，不会清 `audioBlockedByAutoplay`
- 覆盖按钮 `autoplay-unlock`（121-128 行）是 `position: fixed; top:60px; right:16px; z-index:65` 的小胶囊，**不遮挡画面中心**

**风险**：v1 只说"失败时保持 true"。若 `play()` 持续失败（设备被占用、源失效），标志位恒 true → 静音图标常亮 + 胶囊常驻 + 用户点静音开关无效（因为 `effectiveMuted` 已被 `||` 短路）。

**处置**：

1. 失败时除保持 `true`，还要 `el.muted = true`，让"UI 静音态"与"元素静音态"一致。
2. 覆盖按钮文案区分为两种：未解锁 `'点击开启 KTV 伴奏声音'`、解锁失败 `'声音开启失败，点击重试'`。新增 `player.audioUnlockFailed` 布尔态（成功即清）。
3. **自愈路径需写入测试**：`onLoadedMetadata` 会重新调用 `attemptPlay`（第 358 行），成功即置 `false`（324 行）。所以切歌 / 重载媒体能自动恢复，不需要手动重置。

---

### I-04 R-05 加 `:disabled` 会丢失"为何不可用"的解释

**证据**：`KtvKioskView.vue:328-337`：

```js
async function toggleVocal() {
  const canVocal = player.nowPlaying?.song?.hasVocalTrack === true
    || ['DUAL_TRACK', 'DUAL_CHANNEL'].includes(player.audioLayout?.layout)
  if (!canVocal) {
    toast('当前曲目无伴唱音轨')      // ← 加 disabled 后这行永远走不到
    return
  }
  ...
}
```

**影响**：按钮变灰且**再也点不动**，用户得不到"当前曲目无伴唱音轨"这条**唯一解释**。原来虽是可点但被拦截（多一次点击），至少能知道原因；改完变成"不知道为什么灰"。

**处置**：必须补 `title="当前曲目无伴唱音轨"` 与动态 `aria-label`。这样鼠标悬停与读屏都能得到原因。

**顺带排除的风险**：`.disabled` 类没有全局定义——全项目仅 `KtvBottomDock.vue:193` 的 `.dock-circle.disabled`，`ArtistLibraryView.vue` 里另有独立的 `.upload-btn.disabled`（含 `pointer-events:none`）。改 `.dock-circle.disabled` → `.dock-circle:disabled` 是局部改动，**不影响其他组件**。

---

### I-05 R-09 存在既有测试断言，删代码必须同步删测试

**证据**：`stores/player.test.js:430-440`

```js
it('clamps pitch adjustment within -6 to +6 semitones', () => {
  ...
  expect(p.pitchSemitones).toBe(0)
})
```

**影响**：v1 写的是"若有则删"，实际**确认有**。只删 `player.js` 不删此用例 → `npm test` 直接红。

**处置**：删除 `player.js` 的 pitch 成员时，同一次提交内删除该 `it` 块（已确认同文件仅此一处 pitch 断言）。

---

### I-06 R-06 的 rollback 会影响点歌端分类点歌（跨模块回归）

**这是本轮最重要的发现。** `IktvSongMatchBatchService` 的 rollback 链路实际写库：

| 方法 | SQL 动作 | 影响 |
| --- | --- | --- |
| `publishCatalogNumbers`（221-243 行） | `UPDATE songs SET catalog_number = ...` | 改**主表 `songs`** |
| `publishCategories`（248-274 行） | `DELETE FROM iktv_song_categories` + `INSERT INTO iktv_song_categories ... ON CONFLICT DO NOTHING` | **重建分类关联表** |
| `updatePublishedItemHistory`（276-292 行） | `UPDATE iktv_song_match_batch_items` / `..._results` | 改批次历史 |

而 `iktv_song_categories` 正是点歌端 **「高清专区 / 抖音神曲 / 综艺点歌」** 等 Tab 的数据来源（原审计已确认 `IktvReferenceClassificationPolicy` 的标签白名单含 `高清HD歌曲`、`抖音神曲`、八个综艺名）。

**后果**：管理员在网页端点"回滚"后，点歌端的分类 Tab 数据会变——极端情况下回滚到空快照，这些 Tab 会变空。而这**不是 UI bug**，是数据被按预期回退了。若前端无提示、验收不覆盖，会被误判成"点歌端坏了"。

**风险缓解（已存在的保护，需在 UI 明示）**：

- `metadata_locks` 白名单保护：`NOT (song.metadata_locks @> ARRAY['catalogNumber'])` —— **人工锁定的字段不会被回滚覆盖**。
- `ELIGIBLE_ITEM` 限定范围，只处理符合条件的分片。

**处置**：

1. 回滚二次确认文案必须写明："将回退歌曲编号与**分类标签**，点歌端「高清专区/抖音神曲/综艺点歌」等分类入口的数据会随之变化。"
2. 回滚结果必须展示 `RollbackResult` 全部字段，尤其 **`conflictingCategoriesOrNumbers` / `conflictRows`**——回滚**可能产生冲突**，不展示会让用户以为"完全回滚成功"。
3. 验收增加跨模块项（见 6.5）。

**DTO 字段（前端渲染依据，已核实）**：

```java
record FusionStatus(String activeSnapshotVersion, String pausedSnapshotVersion,
                    Long pausedByBatchId, String pauseReason, String pausedAt)
record RollbackResult(long batchId, String state, long catalogNumbersChanged,
                      long categoriesChanged, long conflictingCategoriesOrNumbers,
                      long conflictRows)
record BatchStatus(long id, String batchKind, String snapshotVersion, String state,
                   String publicationState, long totalCount, long processedCount,
                   long matchedCount, long unmatchedCount, long conflictCount,
                   long protectedCount, long outOfScopeCount, long failedCount,
                   long classificationCount, String lastErrorCode,
                   OffsetDateTime createdAt, OffsetDateTime startedAt,
                   OffsetDateTime completedAt, OffsetDateTime rollbackCompletedAt,
                   long rollbackConflictCount)
```

---

### I-07 AdminLayout 侧栏扩展的两个细节

**证据**：

- `.side { width:220px; flex:none; ... }`（137 行）——**没有 `overflow-y:auto`**
- `nav { padding:16px 12px }`（141 行），6 条 `.mi` 各 `min-height:42px; margin-bottom:4px`
- 移动端已有 `nav { display:flex; overflow-x:auto }`（170 行），横向滚动 OK
- `defineProps({ active: ... })` 的注释（66-69 行）只列了 `dashboard | source | ktv | settings`，**已过时**（实际有 6 个值）

**影响**：桌面端加第 7 条后，若视口高度不足，导航会被裁切且无法滚动。

**处置**：给 `.side` 加 `overflow-y:auto`；顺手更新 props 注释为 7 个值。

---

### I-08 已排除的伪风险（核验后无需处理）

| 疑点 | 核验结论 |
| --- | --- |
| 新增 4 个 API 方法会不会破坏 `client.test.js` | ❌ 不会。该文件**未穷举** `api` 的方法集合（grep `Object.keys(api)` 无命中） |
| 新页面 token 过期无法跳登录 | ❌ 不会。`AdminLayout.vue:87` 监听 `home-ktv-admin-auth-required`，新页面套 `AdminLayout` 即自动继承 |
| 新增 `/admin/iktv-fusion` 深链接刷新 404 | ❌ 不会。`SpaController` 兜底支持 3 段，该路由 2 段 |
| 新增 `.test.js` 会拉低覆盖率阈值 | ❌ 不会。`thresholds` 只在 `coreProfile` 下生效，且仅覆盖 19 个 `coreCoverageFiles`；`ktv/` 目录新文件不在其中 |
| `ktv/` 目录新增测试需要新依赖 | ❌ 不需要。沿用 `createApp` + jsdom 文件头注释范式，`package.json` 无变化 |
| 改 `.dock-circle.disabled` 影响其他组件 | ❌ 不会。全项目仅此一处使用该选择器 |

---

## 三、回归影响矩阵

| 改动 | 直接影响 | 间接受影响 | 回归防护措施 |
| --- | --- | --- | --- |
| R-01 房主状态 | `KtvQueueDrawer` | `player.roomHost` 的 WS 合并路径；顶歌/删除按钮可见性 | 加"WS 推送后 error 自动消失"用例；断言 error 态下认领按钮仍在 |
| R-02 解锁失败 | `KtvMediaSurface` 音频态 | `effectiveMuted` 计算、静音按钮交互、`attemptPlay` 自愈 | 加"切歌后成功即清标志"用例；断言 `el.muted` 与标志位一致 |
| R-03 注册横幅 | `KtvKioskView` 布局 | `kiosk-stage` 可用高度、全屏态 `KtvMediaSurface` 覆盖层 | 断言 banner 是 shell 的直接子元素；手动验 720p 竖屏不溢出 |
| R-04 昵称回滚 | `KtvModals.saveNickname` | `user.serverUserId` / `isReady` / 房主判定 | 加"失败后 isReady 仍为 true"用例 |
| R-05 Dock 禁用 | Dock 原唱/伴唱按钮 | `toggleVocal` 的 toast 分支失效 | 加 `title`/`aria-label` 断言；保留 `toggleVocal` 内拦截逻辑 |
| R-06 融合视图 | 新增 admin 页 + 4 接口 | **`songs.catalog_number`、`iktv_song_categories`、点歌端 4 个分类 Tab** | 跨模块验收清单 6.5；冲突计数必须断言 |
| R-07 收藏图标 | `favorites` store | `App.vue` 启动预加载、`ThreeColumnBrowser` 两处调用 | 加"loadFailed 成功后清零"用例 |
| R-08 头像进度 | `ArtistLibraryView` | 4 个调用点 | 加"重试成功后恢复数字"用例 |
| R-09 pitch 清理 | `player.js` / `player.test.js` | 无（零调用方） | 删前 grep 证无引用；同提交删测试 |

---

## 四、TDD 通用约定

### 4.1 测试环境（沿用项目既有范式）

`vite.config.js` 的 `test.environment` 是 `node`，项目已装 `jsdom`，组件测试通过**文件头注释**覆盖环境。**不使用 `@vue/test-utils`**（项目未安装），用 `createApp` + `document` 断言：

```js
// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'

const { apiMock } = vi.hoisted(() => ({ apiMock: { /* 逐个列出被 mock 的方法 */ } }))
vi.mock('../../api/client', () => ({ default: apiMock }))

import Component from './Component.vue'

describe('Component', () => {
  let app, root
  beforeEach(() => {
    root = document.createElement('div')
    document.body.appendChild(root)
    app = createApp(Component)
  })
  afterEach(() => { app.unmount(); root.remove(); vi.clearAllMocks() })
})
```

### 4.2 每项的 TDD 循环

1. **RED**：先写测试，**确认它因"预期原因"失败**（不是 import 错、不是 mock 缺）。记录 `npx vitest run <file>` 的失败输出。
2. **GREEN**：写**最小**实现让该测试通过。
3. **REFACTOR**：全绿前提下整理；涉及公共逻辑先补保护测试。
4. **回归**：`npm test` 全量必须通过。

### 4.3 基线（最先做）

```bash
cd h5 && npm test          # 记录用例数与通过数
cd h5 && npm run build     # 确认构建基线
```

> 当前 `src/components/ktv/` 目录**没有任何测试文件**，本计划的 R-01/R-02/R-05 将是该目录首批组件测试。

---

## 五、逐项 TDD 整改

### R-01 房主状态拉取失败无错误态

**目标行为**：`roomHostStatus` 失败时，抽屉顶部**增补**一行"房主状态获取失败 + 重试"，但**不隐藏**任何操作按钮；WS 推送到达后错误行自动消失。

#### RED

新建 `h5/src/components/ktv/KtvQueueDrawer.hostState.test.js`：

| 用例 | 断言（当前失败原因） |
| --- | --- |
| `房主状态拉取失败时显示错误提示与重试按钮` | mock `api.roomHostStatus` reject；`open=true`；`await nextTick()` ×2；断言容器文本含 `"房主状态"` 且存在文案含 `"重试"` 的 button（**现在失败**：无错误态） |
| `失败时仍保留认领房主按钮` | 同上场景，断言仍存在文案 `"认领房主"` 的 button（**v1 方案会失败**，本条专防 I-02 死锁） |
| `点击重试会再次请求并恢复正确状态` | 首次 reject、二次 resolve `{claimed:true, hostUserId:7, hostNickname:'小明'}`；点击重试后断言显示 `"房主：小明"` 且错误行消失 |
| `WS 推送房主变更后错误行自动清除` | reject 后直接改 `player.roomHost = {claimed:true, hostUserId:7, isHost:true, revision:2}`；`await nextTick()`；断言错误行消失、显示 `"你是房主"` |
| `拉取成功时保持现有行为不回归` | resolve 正常数据，断言无错误行 |

#### GREEN

- 新增 `hostStatus = ref('idle')`，取值 `idle | loading | ready | error`。
- 抽出 `refreshHost()`，替换 136/143 行的重复调用；成功置 `ready`，失败置 `error`。
- `mergeHost()` 内收到有效数据时置 `hostStatus = 'ready'`（覆盖 WS 推送路径）。
- 模板 `host-bar` **内**增加错误行（`v-if="hostStatus === 'error'"`），**不动**认领/释放按钮的 `v-if` 条件。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/components/ktv/KtvQueueDrawer.vue` | 25-31 行（`host-bar`） | 增加错误行 + 重试按钮，**保留原按钮** |
| 同上 | 116 行附近 | 新增 `hostStatus` ref |
| 同上 | 133-146 行 | 抽 `refreshHost()`，替换两处空 catch |
| 同上 | `mergeHost()`（约 120-130 行） | 成功时置 `ready` |
| 同上 | `<style scoped>` | 复用 `.empty-tip.err` 或新增 `.host-error` |
| `src/components/ktv/KtvQueueDrawer.hostState.test.js` | 新建 | 5 条用例 |

---

### R-02 音频解锁失败后标志位不回滚且无提示

**目标行为**：`play()` 失败时 `audioBlockedByAutoplay` 与 `el.muted` **同时**保持静音态，胶囊文案切为"开启失败"，并给出提示；切歌后自动重试可自愈。

#### RED

新建 `h5/src/components/ktv/KtvMediaSurface.unlock.test.js`：

| 用例 | 断言（当前失败原因） |
| --- | --- |
| `解锁失败时保留阻断标志` | `videoEl.play` 改为 reject `DOMException('x','NotAllowedError')`；调 `unlockAudio()`；断言 `player.audioBlockedByAutoplay === true`（**现在失败**：开头已置 false） |
| `解锁失败时 videoEl.muted 保持 true` | 同上；断言 `videoEl.muted === true`（**现在失败**：`el.muted = player.muted` 后未复原） |
| `解锁失败时给出提示` | mock `toast`；断言被调用且文案含 `"声音"`（**现在失败**：静默） |
| `解锁失败后胶囊文案切换为失败态` | 断言 `player.audioUnlockFailed === true`，且胶囊文本含 `"重试"` |
| `解锁成功时清除标志与失败态` | `play` resolve；断言 `audioBlockedByAutoplay === false` 且 `audioUnlockFailed === false` |
| `切歌后媒体加载成功可自愈` | 先制造失败态，再触发 `onLoadedMetadata` 且 `play` resolve；断言标志位恢复 `false`（防 I-03 永久卡死） |

#### GREEN

```js
async function unlockAudio() {
  const el = videoEl.value
  if (!el) return
  el.muted = player.muted
  syncAudioRouter()
  try {
    await channelRouter.resume()
    if (player.isPlaying) await el.play()
    player.audioBlockedByAutoplay = false
    player.audioUnlockFailed = false
  } catch (e) {
    player.audioBlockedByAutoplay = true
    player.audioUnlockFailed = true
    el.muted = true                      // 与标志位保持一致
    toast(e?.name === 'NotAllowedError'
      ? '浏览器仍拦截自动播放，请再点一次'
      : '声音开启失败，请检查设备输出后重试')
  }
}
```

- 删除函数首行的 `player.audioBlockedByAutoplay = false`。
- `stores/player.js` 新增 `audioUnlockFailed: false`；`attemptPlay` 成功分支同步清 `false`。
- 胶囊文案：`{{ player.audioUnlockFailed ? '声音开启失败，点击重试' : '点击开启 KTV 伴奏声音' }}`。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/components/ktv/KtvMediaSurface.vue` | 335-348 行 `unlockAudio()` | 时序调整 + 失败处理 |
| 同上 | 121-128 行 | 胶囊文案三态 |
| 同上 | 320-330 行 `attemptPlay()` | 成功时清 `audioUnlockFailed` |
| `src/stores/player.js` | state | 新增 `audioUnlockFailed` |
| `src/components/ktv/KtvMediaSurface.unlock.test.js` | 新建 | 6 条用例 |

---

### R-03 注册未就绪无 UI 反馈（**位置修正**）

**目标行为**：`user.registrationError` 非空时，**点歌端**出现可见提示条 + 重试，后台页面不受影响，不破坏全屏布局。

#### RED

新建 `h5/src/components/ktv/KtvKioskView.identity.test.js`（**不放在 `App.test.js`**，因为横幅已移出 App）：

| 用例 | 断言（当前失败原因） |
| --- | --- |
| `注册失败时渲染身份提示条` | `user.registrationError='server unavailable'`；mount 后断言 DOM 含 `"身份"`（**现在失败**：无提示） |
| `提示条包含重试按钮` | 断言存在文案含 `"重试"` 的 button |
| `点击重试成功后提示条消失` | 首失败、次成功；点击后断言提示条消失、`user.isReady === true` |
| `注册正常时不渲染提示条` | 保护既有行为 |
| `提示条是 kiosk-shell 的直接子元素` | 断言 `banner.parentElement.classList.contains('ktv-kiosk-shell')`（防 I-01 布局回归） |

#### GREEN

- `KtvKioskView.vue` 模板在 `<KtvHeaderBar>` 之后插入：

```html
<div v-if="user.registrationError" class="identity-banner" role="alert">
  身份初始化失败：{{ user.registrationError }}
  <button type="button" @click="retryIdentity">重试</button>
</div>
```

- 新增 `retryIdentity()`：调 `api.registerUser` 成功则 `markRegistrationSuccess` 并刷新队列，失败则 `markRegistrationFailure`。
- `<style scoped>` 增加 `.identity-banner`：**不要用 `position:fixed`**，作为 flex item 参与布局（`flex:none`），高度约 36px。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/views/KtvKioskView.vue` | `<KtvHeaderBar>` 之后 | 新增 banner |
| 同上 | `<script setup>` | 新增 `retryIdentity()` |
| 同上 | `<style scoped>` | 新增 `.identity-banner`（flex item，非 fixed） |
| `src/App.vue` | **不改动** | 保持原样（v1 的改动撤销） |
| `src/components/ktv/KtvKioskView.identity.test.js` | 新建 | 5 条用例 |

---

### R-04 `user.register()` 清空身份后失败不回滚

**目标行为**：保存昵称失败时，`serverUserId` 与 `isReady` 保持原值。

#### RED

扩展 `h5/src/stores/user.test.js` + 新建 `h5/src/components/ktv/KtvModals.nickname.test.js`：

| 用例 | 断言（当前失败原因） |
| --- | --- |
| `昵称保存失败后可写身份不被清空` | 先 `markRegistrationSuccess({id:17,nickname:'旧'})`，再走失败流程；断言 `isReady === true` 且 `serverUserId === 17`（**现在失败**：`register()` 已置 null） |
| `昵称保存失败后 registrationError 有值` | 断言 `registrationError` 非空（**现在失败**：被清成 `''` 且失败分支不写） |
| `昵称更新失败时回滚本地昵称` | 组件测试：mock `registerUser` reject；`saveNickname()` 后断言 `user.nickname` 仍是旧值 |

#### GREEN（推荐路径 B：改调用点）

`KtvModals.saveNickname()` 去掉会清身份的 `user.register(next)`，改为备份 → 直调 API → 失败回滚：

```js
async function saveNickname() {
  const next = nickDraft.value.trim()
  if (!next) { toast('昵称不能为空'); return }
  savingNick.value = true
  const prev = { nickname: user.nickname, serverUserId: user.serverUserId, error: user.registrationError }
  user.ensureToken()
  try {
    const res = await api.registerUser(user.clientToken, next)
    if (!user.markRegistrationSuccess(res)) throw new Error('昵称更新失败')
    toast(`已更新昵称：${user.nickname}`)
  } catch (e) {
    user.nickname = prev.nickname
    user.serverUserId = prev.serverUserId
    user.registrationError = prev.error
    if (prev.nickname) user.setNickname(prev.nickname)
    toast(e.message || '保存昵称失败')
  } finally {
    savingNick.value = false
  }
}
```

> 保留 `register()` 的"清身份"语义不动——它在首次注册场景是必需的。在调用点区分两种语义，比改 store 全局语义风险低。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/components/ktv/KtvModals.vue` | 281-297 行 `saveNickname()` | 去掉 `user.register()`，改为直调 API + 失败回滚 |
| `src/stores/user.test.js` | 新增 describe | 2 条用例 |
| `src/components/ktv/KtvModals.nickname.test.js` | 新建 | 1 条用例 |

---

### R-05 Dock「原唱/伴唱」伪禁用（**补解释性反馈**）

**目标行为**：`canVocal === false` 时按钮真正禁用，且通过 `title` / `aria-label` 说明原因。

#### RED

新建 `h5/src/components/ktv/KtvBottomDock.vocal.test.js`：

| 用例 | 断言（当前失败原因） |
| --- | --- |
| `无伴唱音轨时按钮真正禁用` | `player.nowPlaying={song:{hasVocalTrack:false}}`、`audioLayout={layout:'NORMAL_STEREO'}`；断言 `disabled === true`（**现在失败**：只有 class） |
| `无伴唱音轨时按钮带 aria-disabled` | 断言 `getAttribute('aria-disabled') === 'true'` |
| `禁用时 aria-label 说明原因` | 断言 `getAttribute('aria-label')` 含 `"无伴唱音轨"`（防 I-04 信息丢失） |
| `禁用时 title 说明原因` | 断言 `getAttribute('title')` 含 `"无伴唱"` |
| `有伴唱音轨时按钮可用` | `hasVocalTrack:true`；断言 `disabled === false` 且 `aria-label` 含 `"切换"` |
| `禁用状态下点击不触发 toggle-vocal` | `button.click()`；断言 emit 未被调用 |

#### GREEN

`KtvBottomDock.vue:11-20`：

```vue
<button
  type="button"
  class="dock-circle"
  :class="{ active: player.vocalMode === 'accompaniment' }"
  :disabled="!canVocal"
  :aria-disabled="!canVocal"
  :title="canVocal ? '' : '当前曲目无伴唱音轨'"
  :aria-label="canVocal
    ? (player.vocalMode === 'original' ? '当前原唱，点击切换伴唱' : '当前伴唱，点击切换原唱')
    : '当前曲目无伴唱音轨'"
  @click="$emit('toggle-vocal')"
>
```

样式 `.dock-circle.disabled`（193-195 行）→ `.dock-circle:disabled`，补 `cursor: not-allowed`。

> **保留** `KtvKioskView.toggleVocal()` 内的 `if (!canVocal) { toast(...) }` 分支——`KtvMediaSurface` 全屏控台仍依赖它，双保险无副作用。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/components/ktv/KtvBottomDock.vue` | 11-20 行 | 加 `:disabled` / `:aria-disabled` / `:title` / 动态 `aria-label` |
| 同上 | 193-195 行 | 选择器改 `:disabled` + `cursor` |
| `src/components/ktv/KtvBottomDock.vocal.test.js` | 新建 | 6 条用例 |

---

### R-06 IKTV 引用融合管理页（**已确认由网页端承载**）

#### 6.1 页面设计

| 区块 | 内容 | 数据来源 |
| --- | --- | --- |
| 当前快照卡 | `activeSnapshotVersion`；无则"尚未发布任何快照" | `FusionStatus` |
| 暂停状态卡 | `pausedSnapshotVersion` / `pausedByBatchId` / `pauseReason` / `pausedAt`；无则"未暂停" | `FusionStatus` |
| 操作区 | 「恢复融合」仅 `pausedSnapshotVersion != null` 时可用；「查询批次」按 batchId；「回滚」需明确 batchId | — |
| 批次详情 | `state` / `publicationState` / `totalCount` / `processedCount` / `matchedCount` / `unmatchedCount` / `conflictCount` / `protectedCount` / `failedCount` / `rollbackConflictCount` / `lastErrorCode` | `BatchStatus` |
| 回滚结果 | `catalogNumbersChanged` / `categoriesChanged` / `conflictingCategoriesOrNumbers` / `conflictRows` | `RollbackResult` |
| 风险提示条 | 常驻：融合会改写歌曲编号与分类标签，**影响点歌端分类入口** | 静态文案 |

**回滚二次确认文案（必须包含，防 I-06）**：

> 即将回滚批次 #{id} 的融合结果。
> 该操作会**回退歌曲编号与分类标签**，点歌端「高清专区 / 抖音神曲 / 综艺点歌」等分类入口的数据会随之变化。
> 已被元数据锁（metadata_locks）锁定的字段不会被覆盖。
> 回滚可能产生冲突，冲突条目将保持原值并在结果中报告。
> 确定继续？

**回滚结果必须展示冲突计数**：`conflictRows > 0` 时醒目提示"有 N 条冲突未回滚，保持原值"。

#### 6.2 RED

新建 `h5/src/views/admin/IktvFusionView.test.js`：

| # | 用例 | 断言 |
| --- | --- | --- |
| 1 | `加载并展示当前快照版本` | status → `{activeSnapshotVersion:'v12'}`；断言含 `v12` |
| 2 | `无快照时显示空态` | `activeSnapshotVersion:null`；断言含 `"尚未"` |
| 3 | `非暂停态恢复按钮禁用` | `pausedSnapshotVersion:null`；断言"恢复"按钮 `disabled` |
| 4 | `暂停态恢复按钮可用` | `pausedSnapshotVersion:'v11'`；断言可点 |
| 5 | `点击回滚弹出二次确认且不立即调用接口` | 断言 `confirmDialog` 被调用；rollback **未**被调用 |
| 6 | `确认文案包含分类影响提示` | 断言 `confirmDialog` 首参含 `"分类"` 与 `"点歌端"`（防 I-06） |
| 7 | `确认后才调用回滚接口` | resolve `true`；断言 rollback 调用一次 |
| 8 | `取消确认不调用接口` | resolve `false`；断言未被调用 |
| 9 | `回滚结果显示冲突计数` | resolve `{conflictRows:3,categoriesChanged:12}`；断言文本含 `"3"` 与 `"冲突"` |
| 10 | `接口失败时提示错误` | rollback reject；断言 `alertDialog` 被调用 |
| 11 | `状态拉取失败时显示错误与重试` | status reject；断言含 `"重试"` |

扩展 `h5/src/api/client.test.js` 新增 4 条：

| 方法 | 断言 |
| --- | --- |
| `adminIktvFusionStatus()` | `GET /api/admin/iktv-reference/fusion/status` |
| `adminIktvFusionBatch(7)` | `GET .../batches/7` |
| `adminIktvFusionResume()` | `POST .../resume` |
| `adminIktvFusionRollback(7)` | `POST .../batches/7/rollback` |

#### 6.3 GREEN

1. `api/client.js` 补 4 个封装（`/admin` 前缀自动注入 `X-Admin-Token`）。
2. 新建 `views/admin/IktvFusionView.vue`（约 200 行）：`<AdminLayout active="iktv">` 包裹，按 6.1 布局。
3. `router/index.js` 注册：`{ path:'/admin/iktv-fusion', name:'admin-iktv-fusion', component: () => import('../views/admin/IktvFusionView.vue'), meta: { public: true, admin: true } }`（照抄现有范式）。
4. `AdminLayout.vue`：nav 增加第 7 条；`.side` 加 `overflow-y:auto`；更新 props 注释。

#### 6.4 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/api/client.js` | `api` 对象内 | 新增 4 方法 |
| `src/api/client.test.js` | 新增 describe | 4 条 |
| `src/views/admin/IktvFusionView.vue` | 新建 | 视图 |
| `src/views/admin/IktvFusionView.test.js` | 新建 | 11 条 |
| `src/router/index.js` | 路由表 | 新增 1 条 |
| `src/views/admin/AdminLayout.vue` | 12-19 行 nav、66-69 行注释、137 行 `.side` | 新增导航 + `overflow-y` |
| `backend/**` | **不改动** | 端点已具备 |

#### 6.5 跨模块验收（**本项独有，必须执行**）

| # | 验收动作 | 通过标准 |
| --- | --- | --- |
| V1 | 打开融合页，读取 status | 正常渲染，无 401 |
| V2 | 查询一个已存在批次 | 字段正确显示 |
| V3 | 点回滚 → 确认框出现 | 文案含"分类""点歌端" |
| V4 | 确认回滚 | 结果展示 `catalogNumbersChanged` / `categoriesChanged` / `conflictRows` |
| V5 | **回点歌端，依次打开「高清专区」「抖音神曲」「综艺点歌」「主题点歌」** | 四个 Tab 均正常加载；数据可变化，**不允许空白无提示或报错** |
| V6 | 点歌端随机点一首歌加入队列 | 队列正常，无 500 |
| V7 | 后台「KTV曲库」按关键词搜索 | 结果正常，`catalog_number` 变化符合预期 |

> V5 是本计划中**唯一必须跨到点歌端**的验收项，因 I-06 指出的 `iktv_song_categories` 重建会直接影响这四个 Tab。

---

### R-07 收藏图标状态失败无提示（P2）

**目标行为**：`favorites.load()` 失败且从未成功过时，收藏 Tab 出现"收藏状态同步失败 + 重新同步"，成功后提示消失。

#### RED

新建 `h5/src/components/ktv/KtvThreeColumnBrowser.favorites.test.js`：

| 用例 | 断言（当前失败） |
| --- | --- |
| `收藏状态同步失败时提示可重试` | mock `api.favoriteIds` reject；断言出现含 `"收藏状态"` 的提示（**现在失败**） |
| `重试成功后提示消失` | 首 reject、次 resolve；点重试后断言提示消失 |
| `收藏列表本身失败时仍走原有错误提示` | mock `api.favorites` reject；断言含 `"收藏读取失败"`（保护既有 `loadError` 逻辑不被误改） |

#### GREEN

- `stores/favorites.js`：state 增 `loadFailed: false`；`load()` catch 置 `true`，成功置 `false`（保留 `loadedFor` 不更新的既有语义）。
- `KtvThreeColumnBrowser`：`activeTab === 'favorites' && favorites.loadFailed` 时列表上方渲染提示条 + "重新同步"按钮。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/stores/favorites.js` | state + `load()` | 新增 `loadFailed` |
| `src/components/ktv/KtvThreeColumnBrowser.vue` | 收藏 Tab 顶部 | 新增提示条 |
| `src/components/ktv/KtvThreeColumnBrowser.favorites.test.js` | 新建 | 3 条用例 |

---

### R-08 头像进度拉取失败无提示（P2）

#### RED

扩展 `h5/src/views/admin/ArtistLibraryView.test.js`：

| 用例 | 断言（当前失败） |
| --- | --- |
| `进度拉取失败时显示错误与重试` | `adminArtistAvatarProgress` reject；断言 DOM 含 `"进度"` 且含 `"重试"` |
| `重试成功后恢复展示进度` | 首 reject、次 resolve；点重试后断言显示进度数字 |
| `列表加载不受进度失败影响` | 断言歌手列表仍正常渲染（保护既有 `load()` 路径） |

#### GREEN

`ArtistLibraryView.vue` 新增 `avatarProgressState`（`idle|ready|error`），catch 置 `error`，模板增加 error 分支 + 重试按钮。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/views/admin/ArtistLibraryView.vue` | 109-116 行 + 进度渲染区 | 新增状态与错误分支 |
| `src/views/admin/ArtistLibraryView.test.js` | 新增 3 条 | — |

---

### R-09 pitch 死代码清理（P2）

**目标行为**：删除未被使用、且产品明确不支持的 pitch 状态机，**不改变任何用户可见行为**。

#### 步骤

1. **证明无引用**（已在本轮验证，执行时再跑一次）：

```bash
cd h5 && grep -rn "pitchSemitones\|\.adjustPitch\|\.setPitch\|\.resetPitch" src --include=*.vue --include=*.js | grep -v "stores/player"
# 期望：无输出
```

2. **删除代码**：`stores/player.js` 的 `clampPitch()`（7-11 行）、`pitchSemitones` state（53 行）、`applySnapshot` 内 `this.pitchSemitones = 0`（209 行）、`adjustPitch` / `setPitch` / `resetPitch`（320-332 行）。
3. **同步删除测试**：`stores/player.test.js:430-440` 的 `clamps pitch adjustment within -6 to +6 semitones`（**已确认存在，必须删**，防 I-05）。
4. **保留**：`KtvKioskView` 的 `adjustPitch` / `resetPitch` toast 与 7 个按钮——它们是明确的产品告知。

#### 修改范围

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `src/stores/player.js` | 7-11、53、209、320-332 | 删除 |
| `src/stores/player.test.js` | 430-440 | 删除该 `it` 块 |
| `src/components/ktv/KtvBottomDock.vue` | 75、80 行 | 可选：加 `title` 复用 mixer 文案 |
| `src/components/ktv/KtvMediaSurface.vue` | 91、92 行 | 可选：加 `title` |

---

## 六、执行顺序与依赖

```text
第 0 步  基线：npm test（记录用例数）/ npm run build
   │
第 1 批（互不依赖，可并行）—— 纯组件/函数级，风险最低
   ├── R-05 Dock 禁用 + 解释性反馈
   ├── R-02 解锁失败（含 player.js 新增 audioUnlockFailed）
   └── R-04 昵称回滚
   │
第 2 批
   ├── R-01 房主状态（含 mergeHost 联动）
   └── R-03 注册横幅（改在 KtvKioskView）
   │
第 3 批（P2，可合并一个 PR）
   ├── R-07 收藏图标
   ├── R-08 头像进度
   └── R-09 pitch 死代码（删代码 + 删测试须同一提交）
   │
第 4 批（独立 PR，跨模块影响最大）
   └── R-06 IKTV 融合页 + 跨模块验收 V1~V7
```

**依赖说明**：

- R-09 与 R-02 都改 `stores/player.js`（一个删 pitch、一个加 `audioUnlockFailed`）。若并行开发，**先合 R-02 再合 R-09**，避免冲突。
- R-01 与 R-03 无代码耦合，但建议同批上线，让"身份问题"整体可观测。
- R-06 必须**最后一批**且独立 PR：它是唯一会改动持久化数据（`songs` / `iktv_song_categories`）的功能。

---

## 七、总验收清单

| # | 项 | 验收方式 |
| --- | --- | --- |
| 1 | 每项 RED 阶段**实际观察过失败** | 保留 `npx vitest run <file>` 失败输出 |
| 2 | `cd h5 && npm test` 全绿 | 用例数 = 基线 + 新增 − 删除（R-09 删 1） |
| 3 | `cd h5 && npm run build` 通过 | 产物写入 `backend/src/main/resources/static/m` |
| 4 | 无新增静默 catch | `grep -rn "catch(() => {})\|catch (_) {}" src` 结果不增加 |
| 5 | 新增组件测试均带 `// @vitest-environment jsdom` | 逐文件检查 |
| 6 | 未引入 `@vue/test-utils` | `package.json` 依赖无变化 |
| 7 | 7 个 pitch 按钮行为未变 | 仍弹原 toast |
| 8 | 收藏列表 `loadError` 逻辑未破坏 | R-07 第 3 条用例 |
| 9 | 720p 竖屏点歌端无滚动条 | DevTools 720×1280，确认 `scrollHeight === clientHeight` |
| 10 | 后台 9 个页面不出现身份横幅 | 构造 `registrationError` 后访问 `/admin` |
| 11 | **R-06 跨模块 V1~V7 全部通过** | 见 6.5 |

---

## 八、风险与回滚预案

| 风险 | 概率 | 影响 | 预案 |
| --- | --- | --- | --- |
| R-06 回滚后点歌端分类 Tab 空 | 中 | 分类入口不可用（**数据被按预期回退，非代码 bug**） | 上线前确认存在可恢复快照；回滚页展示冲突数；出问题用 `resume` 或重新发布批次恢复 |
| R-02 标志位在特殊设备上仍卡 true | 低 | 静音图标常亮 | 切歌即自愈（`onLoadedMetadata` → `attemptPlay`），已加测试覆盖 |
| R-01 error 态频繁打扰 | 中 | 每次打开抽屉都提示 | 连续失败 2 次后才展示；成功一次即清 |
| R-03 banner 挤压内容区 | 低 | 竖屏可见行数减少约 1 行 | 高度 ≤ 36px；提供关闭按钮（仅本次会话隐藏） |
| R-05 按钮变灰后用户困惑 | 中 | 无法切换原唱/伴唱 | `title` + `aria-label` 说明；调音台可看到音轨布局状态 |
| 新增 7 条测试文件拖慢 CI | 低 | 测试时长增加 | 全部 mock，无真实网络；预计增量 < 3s |

---

## 九、明确不在本次范围

| 项 | 原因 |
| --- | --- |
| 实现真正的升降调（Web Audio 变调 / 服务端转码） | 产品已明确"网页端不支持"，三种路径成本差异巨大，需独立立项 |
| 补齐 `/admin/imports`、`/admin/vocal-review`、`gender-dictionary/import`、`files/{id}/vocal-track` 的 UI | 属"能力补全"而非"缺陷修复"，无明确使用场景；建议后端标注为内部/运维接口 |
| `/api/admin/scan/rebind` | **该端点不存在**（原报告事实错误），无可整改对象 |
| 引入 `@vue/test-utils`、改造测试栈 | 现有 `createApp` 范式可用，改造收益不足 |
| 全局统一错误态组件 | 待 R-01/R-07/R-08 落地后，若出现 3 处以上重复结构再抽取，避免过早抽象 |

---

*本文档为整改计划（v2），尚未修改任何源码。R-06 已确认由网页端承载，需按 6.5 的跨模块验收清单执行。*
