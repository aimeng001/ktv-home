# Home KTV

**中文** | [English](README_EN.md)

## 界面预览

| 手机点歌首页 | Android TV 待机页 | 曲库与服务仪表盘 |
| --- | --- | --- |
| ![手机点歌首页](docs/images/mobile-songbook.png) | ![Android TV 待机页](docs/images/tv-player.png) | ![管理仪表盘](docs/images/admin-dashboard.png) |
| 搜索、分类、收藏、点歌和遥控都在手机浏览器完成。 | 实机待机界面展示点歌二维码、服务状态与推荐歌曲。 | 统一查看曲库、转码任务和播放服务状态。 |

Home KTV 是一套运行在家庭 NAS 或 Linux 主机上的局域网点歌系统。电视负责播放，手机通过微信扫码进入点歌页，服务端管理曲库、队列、歌词、播放记录和系统设置。

系统由三个客户端组成：

- **服务端**：Spring Boot、PostgreSQL、FFmpeg/FFprobe、WebSocket
- **手机端**：Vue 3 H5 点歌页和管理后台，无需安装 App
- **电视端**：Android TV 客户端，基于 Media3/ExoPlayer

> 项目面向可信家庭局域网；管理后台支持独立密码，但普通点歌和遥控仍按局域网信任模型工作，请勿直接暴露到互联网。

## 典型使用流程

1. 在 NAS 或 Linux 主机启动 Home KTV，通过管理后台扫描并整理本地曲库。
2. Android TV 客户端自动发现局域网服务，连接后在大屏上显示点歌二维码。
3. 家人用微信或手机浏览器扫码加入，各自搜歌、收藏和点歌，无需安装 App。
4. 点歌队列、播放进度、歌词、音量与原唱/伴唱状态在电视和手机间实时同步。
5. 演唱结束后可在手机查看最近演唱，管理员则可在后台维护歌曲、歌手、歌单和转码任务。

## 本版本更新

- **源文件流水线**：扫描时重新判断文件是否需要转码；兼容文件直接移动到 KTV 曲库并清理原始管理记录，不兼容文件保留在原始音乐管理中等待转码。批量转码支持进度和插队，自动清理完成后会提醒重新扫描源路径。
- **KTV 曲库管理**：歌曲列表使用分页查询和固定操作区；元数据刮削支持全量批次、暂停、继续、进度明细、置信度自动写入、人工审核、手工编辑、单曲重新匹配和封面回显。
- **歌手库**：按规范化名称汇总歌手，支持性别状态、批量 AI 分析和人工复核；同名歌手审核时会展示代表歌曲作为判断依据。
- **手机点歌页**：歌曲列表统一显示封面，语种和分类提升为主要入口，歌手支持男歌手、女歌手筛选，并可把歌曲加入已有歌单。
- **主题歌单**：使用已整理的曲库元数据生成可编辑预览，单个歌单最多 100 首；不足 100 首也可保存，AI 生成的歌单支持删除。
- **设置中心**：改为分类侧栏、搜索和右侧内容区，支持基础配置、AI 模型、入库与转码、TV 显示、音乐元数据和数据维护分类及深链接。
- **AI 配置与降级**：支持任意 OpenAI-compatible 地址和模型 ID、模型列表探测、单/双模型、能力测试和并发限制。未配置密钥或调用失败时，具备本地规则的解析任务自动降级；没有等价本地能力的 AI 操作会提示管理员先配置模型。
- **发布与升级**：发布流水线只构建签名 Release APK，将 32 位和 64 位安装包内置到 Docker 镜像；管理后台按版本显示公告，TV 连接后可下载适合设备架构的安装包并打开系统安装界面。
- **升级安全**：Flyway V15 保留历史源记录，不执行清表或批量删除；迁移安全测试会阻止直接提交 `DELETE FROM`、`TRUNCATE TABLE` 和 `DROP TABLE/COLUMN`。

## 主要功能

### 手机点歌与遥控

- 微信或浏览器扫码进入，无需注册
- 支持歌名、歌手、中文、全拼和拼音首字母搜索
- 点歌、顶歌、删除自己的歌曲、智能打散和多人队列
- 播放、暂停、重唱、切歌、音量及原唱/伴唱切换
- 收藏、最近演唱、一键再唱、热门榜单和公开歌单
- 逐行 LRC 与增强 LRC 逐字歌词
- 鼓掌、欢呼、倒彩、干杯等现场音效

### Android TV 播放

- 播放 MP4、MKV、MPEG、MP3、FLAC 等常见媒体
- 支持 MPEG-2、MP2 和双音轨 KTV 视频
- 原唱/伴奏无重新加载切换
- 当前句与下一句双行歌词，当前句连续扫色高亮
- 遥控器控制播放、队列、音量和原伴唱
- 断线重连、状态恢复、待机轮播和防烧屏微移
- 播放页显示“微信扫码点歌”二维码
- 支持 Android 8.0（API 26）及以上版本

### 曲库管理

- 原始素材分析、MD5 去重、自动直拷和批量转码
- 使用 FFprobe 识别容器、编码、时长、分辨率和音轨
- 自动读取媒体标签、封面及同名歌词侧车文件
- 识别 KTV 视频、普通 MV 和纯音频
- 音轨标记纠正、歌曲编辑、重新解析及失效文件处理
- AI 辅助分类、主题歌单和点唱统计
- PostgreSQL 持久化及数据库备份/恢复

## 系统结构

```text
手机浏览器 / 微信
        │ HTTP + WebSocket
        ▼
Home KTV 服务端 ───── PostgreSQL
        │
        ├── /source-music  原始素材目录
        ├── /music         可点播曲库目录
        │
        └── Android TV     视频、音轨、歌词和控制
```

服务端默认使用以下入口：

| 用途 | 地址 |
| --- | --- |
| 手机点歌 | `http://<主机IP>:8080/m` |
| 管理后台 | `http://<主机IP>:8080/m/admin` |
| 健康检查 | `http://<主机IP>:8080/api/health` |

## 快速开始

### 环境要求

- 支持 Docker Compose 的 NAS、Linux 主机或 Docker Desktop
- 建议至少 1 GB 可用内存
- 手机、Android TV 和服务端位于同一局域网
- Android TV 8.0（API 26）或更高版本

### 1. 配置目录和密码

```bash
git clone <仓库地址>
cd home-ktv
cp .env.example .env
```

编辑 `.env`，至少确认以下配置：

```dotenv
KTV_SOURCE_MUSIC_DIR=/volume1/home-ktv/source-music
KTV_MUSIC_DIR=/volume1/home-ktv/music
KTV_DB_PASSWORD=请替换为强密码
KTV_ADMIN_PASSWORD=请替换为管理后台强密码
```

- `KTV_SOURCE_MUSIC_DIR`：Managed 模式下放置未经处理的原始视频和音频。
- `KTV_MUSIC_DIR`：存放已直拷或转码完成、可以点播的文件。
- `KTV_ADMIN_PASSWORD`：管理后台密码。设置后访问 `/m/admin` 会先登录；普通搜歌、点歌、播放和遥控仍不需要账号。

Managed 模式下两个目录不要配置成同一路径，服务端会向曲库目录写入处理结果，请确保容器具有写权限。

### 选择曲库模式

Home KTV 支持两种互相独立的曲库部署方式，请根据你的文件来源选择对应的 Compose 文件。

#### 已有 NAS 曲库：只读接入

如果 NAS 中已经有可直接播放的歌曲，并且不允许 Home KTV 改动源文件，请使用：

```bash
docker compose -f docker-compose.nas.yml up -d --wait
```

这个文件使用 `KTV_LIBRARY_MODE=EXTERNAL_READ_ONLY`，并将 `/source-music` 以 `read_only: true` 挂载。服务端只建立索引并读取/播放现有文件；源曲库不参与导入流水线。

如果 NAS 已经有现成曲库，例如 `/volume1/KTV`，请直接设置
`KTV_SOURCE_MUSIC_DIR=/volume1/KTV` 指向该目录。外部只读模式只能读取和播放已有文件，
不要复制、不要移动、不要重命名、不要删除、不要覆盖源文件，也不要对源文件转码或执行自动清理。

#### Managed：导入和整理源文件

如果希望 Home KTV 负责导入、直拷、转码和整理源文件，请使用：

```bash
docker compose -f docker-compose.prebuilt.yml up -d --pull always --wait
```

这个文件默认使用 `KTV_LIBRARY_MODE=MANAGED`。请确保 `KTV_SOURCE_MUSIC_DIR` 和 `KTV_MUSIC_DIR` 是不同目录，并允许容器按 Managed 流程写入。

### 2. 启动服务

请使用上面选择的曲库模式对应的 Compose 文件。选择 `MANAGED` 模式时，推荐直接拉取 GitHub Actions 发布的多架构镜像，无需在 NAS 或主机上编译：

```bash
docker compose -f docker-compose.prebuilt.yml up -d --pull always --wait
```

默认使用 `ghcr.io/aimeng001/ktv-home:latest`。生产环境可在 `.env` 中将
`KTV_RELEASE_IMAGE` 设置为具体的发布标签，以避免 `latest` 自动变化。

生产 Compose 默认给应用容器 4GB 内存，并给 JVM 2GB 堆；OOM 时会把诊断堆转储到
`KTV_DATA_DIR` 对应的 `/data` 目录后退出，由 Docker 自动重启。若 `.env` 中已有旧的
`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -Xmx512m`，请删除或改为
`.env.example` 中的新值，否则旧值会覆盖安全默认值。管理员登录后可访问
`/api/admin/diagnostics/memory` 查看堆内存和扫描进度。

选择 `EXTERNAL_READ_ONLY` 模式时，请继续使用上面的 `docker-compose.nas.yml` 命令。需要从源码构建 Managed 模式时使用：

```bash
docker compose up -d --build --wait
```

确认容器健康：

```bash
docker compose ps
curl http://127.0.0.1:${KTV_HTTP_PORT:-8080}/api/health
```

默认开放：

- TCP `8080`：H5、管理后台、API、WebSocket 和媒体流
- UDP `18888`：Android TV 局域网自动发现

NAS 防火墙需要允许这两个端口。UDP 发现协议固定使用 `18888`；只有 HTTP 服务端口可以在 `.env` 中自定义。

### 3. 导入歌曲（仅 MANAGED 模式）

以下步骤仅适用于 `MANAGED` 模式。已有 NAS 曲库请按上面的 `EXTERNAL_READ_ONLY` 只读接入方式部署。

1. 把原始歌曲放入 `KTV_SOURCE_MUSIC_DIR`。
2. 打开 `http://<主机IP>:8080/m/admin`。
3. 在仪表盘执行“扫描源路径”。
4. 兼容文件会自动直拷到曲库；不兼容文件进入待转码列表。
5. 在“原始音乐管理”中执行单首、选中或批量转码。
6. 在“KTV 曲库”中检查歌名、歌手、媒体类型和原唱/伴奏音轨。

扫描只负责分析、去重和直拷，不会自动启动耗时转码。批量转码进度可在管理后台查看，任务运行时支持把指定歌曲插到下一首处理。

源目录自动监听默认关闭。是否启用请在管理后台“系统设置”中的“源目录自动扫描”开关调整；关闭时只有手动点击“扫描源路径”才会扫描，不通过 Compose 或环境变量配置。

以下自动清理流程仅适用于 `MANAGED` 模式：确认入库结果后，可点击批量转码按钮右侧的“自动清理”释放原始素材目录空间。系统只会删除已成功入库、关联曲库记录有效且曲库输出文件真实存在的源文件；待转码、失败、重复、未识别、曲库文件缺失或路径校验不通过的素材会保留。转码任务运行期间不能执行自动清理。清理完成后，请回到仪表盘重新执行“扫描源路径”，同步原始目录中的最新文件。

### 4. 安装 Android TV 客户端

正式发布镜像已经内置同版本的 32 位（`armeabi-v7a`）和 64 位（`arm64-v8a`）
Release APK。发布流水线使用发布标签生成 `versionName`，使用 GitHub Actions 运行序号生成
单调递增的 `versionCode`，并将相同版本信息写入服务端镜像和两份 APK。

后端通过 `GET /api/release` 返回版本、公告和两个架构的安装包信息。公告 ID 默认等于版本号，
所以每次发布新版本都会再次显示。管理后台中的“稍后提醒”只在当前浏览器会话内隐藏公告，
“标记已读”会在当前浏览器保存该公告 ID，直到公告 ID 或版本号变化。公告仅在启用且镜像内
至少存在一份 APK 时弹出；源码开发镜像未放入 APK 时不会显示无效的下载公告。

默认公告会提示 Android TV APK 更新；升级后请重新扫描曲库。
使用 `EXTERNAL_READ_ONLY` 时保持 NAS 源文件不变；使用 `MANAGED` 时按管理页面提示执行相应维护操作。
公告配置随镜像内的 `application.yml` 发布，不依赖用户更新 `docker-compose.yml` 或 `.env`；拉取新镜像即可获得新版本号和公告内容。

TV 每次连接服务端成功后会检查 `versionCode`。版本不一致时根据设备 ABI 选择安装包，用户点击
“去下载”后，客户端会校验下载大小、申请未知来源安装权限，并打开系统安装程序。查询或下载失败
不会影响播放，下载失败时可以重试。安装包也可直接访问：

```text
http://<主机IP>:8080/api/release/tv/apk/armeabi-v7a
http://<主机IP>:8080/api/release/tv/apk/arm64-v8a
```

下载文件名分别为 `home-ktv-tv-<版本号>-armeabi-v7a.apk` 和
`home-ktv-tv-<版本号>-arm64-v8a.apk`。

首次安装 Release APK 后，后续版本必须继续使用同一签名证书。历史 Debug APK 使用
`.debug` 包名且签名不同，不能被 Release APK 直接覆盖，需要先卸载一次 Debug 版本再安装
Release 版本。卸载会清除 TV 端保存的服务端地址，歌曲和服务端数据不受影响。

本地构建 Debug APK 需要 JDK 17 和 Android SDK：

```bash
cd android-tv
./gradlew testDebugUnitTest assembleDebug
```

APK 输出位置：

```text
android-tv/app/build/outputs/apk/debug/app-debug.apk
```

通过 ADB 安装：

```bash
adb connect <TV_IP>:5555
adb install -r android-tv/app/build/outputs/apk/debug/app-debug.apk
```

也可以通过 U 盘或电视文件管理器安装。首次启动会尝试自动发现服务端；发现失败时填写 `<主机IP>:8080`。部分电视盒子需要额外允许未知来源、自启动和后台运行。

### 5. 开始点歌

TV 连接成功后会显示二维码。手机使用微信扫码进入点歌页，选择歌曲后电视自动播放；队列、播放状态、歌词和遥控操作通过 WebSocket 实时同步。

## 媒体与歌词

### 文件命名

系统优先读取媒体标签，标签缺失时从文件名推断歌手和歌名。推荐格式：

```text
歌手-歌名-语种-分类.mkv
```

旧的 `歌手 - 歌名` 格式仍兼容，但不作为新文件的推荐格式。

示例：

```text
source-music/
├── 周杰伦-晴天-国语-流行.mkv
├── S.H.E-Super Star-英语-流行.mpg
└── Beyond-海阔天空-粤语-摇滚.mkv
```

同一首歌存在多个版本时，双音轨 KTV 视频优先于普通 MV 和纯音频。

### 双音轨约定

推荐的视频音轨顺序：

1. 原唱
2. 伴奏

同时建议写入音轨标题 `原唱`、`伴奏` 和语言标签 `zho`。系统会自动判断伴奏轨；识别错误时可在手机遥控页纠正并保存。

仓库提供一个基础声道相减脚本，可为满足声道条件的立体声 MV 生成双音轨文件：

```bash
./scripts/make_ktv_mv.sh input.mp4 output.mp4
```

该脚本不是 AI 人声分离，效果取决于原音频的声道混音方式，不能替代官方伴奏或专业分轨。

### 歌词侧车文件

歌词文件与媒体同名并放在同一目录：

```text
周杰伦 - 晴天.mp4
周杰伦 - 晴天.lrc
```

支持普通逐行 LRC：

```text
[00:12.50]故事的小黄花
```

支持增强 LRC 逐字时间：

```text
[00:12.50]<00:12.50>故<00:12.80>事<00:13.10>的<00:13.35>小<00:13.60>黄<00:13.90>花
```

增强 LRC 会在 TV 上以整句连续扫色显示，手机歌词页也会按字同步。

## 配置参考

常用环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `KTV_SOURCE_MUSIC_DIR` | `./source-music` | 宿主机原始素材目录 |
| `KTV_MUSIC_DIR` | `./music` | 宿主机可点播曲库目录 |
| `KTV_DATA_DIR` | `./data` | 宿主机应用数据目录 |
| `KTV_PG_DIR` | `./postgres` | 宿主机 PostgreSQL 数据目录 |
| `KTV_HTTP_PORT` | `8080` | Web、API、WebSocket 和媒体流端口 |
| `KTV_DISCOVERY_NAME` | `家庭KTV` | TV 发现列表中的名称 |
| `KTV_DB_NAME` | `ktv` | PostgreSQL 数据库名 |
| `KTV_DB_USER` | `ktv` | PostgreSQL 用户名 |
| `KTV_DB_PASSWORD` | `ktv` | PostgreSQL 密码，正式部署必须修改 |
| `KTV_IMAGE_REGISTRY` | `docker.m.daocloud.io` | Docker 基础镜像仓库前缀 |
| `KTV_APP_IMAGE` | `home-ktv:latest` | 应用镜像名称 |
| `KTV_RELEASE_IMAGE` | `ghcr.io/aimeng001/ktv-home:latest` | 预编译 Compose 使用的 GitHub 容器镜像 |
| `JAVA_TOOL_OPTIONS` | `-Xms256m -Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data -XX:+ExitOnOutOfMemoryError` | 容器 JVM 内存与 OOM 行为 |
| `KTV_APP_MEMORY_LIMIT` | `4g` | 应用容器内存上限 |

二维码默认使用 TV 访问服务端时的局域网 Host 地址。若网络中存在反向代理或多个网卡，可在管理后台设置“展示地址”，例如 `192.168.1.10:8080`。

## 硬件转码

默认使用 CPU 转码。Linux 主机可通过以下方式透传 VAAPI 设备：

> **验证范围：**目前只在 Intel 核显上验证了 VAAPI H.264 / HEVC 硬件编码，
> 使用 Intel `iHD` 驱动。AMD VAAPI 和 Rockchip RK MPP 尚未经过实机验证，
> 相关 Compose 配置仅表示支持设备透传，不保证预编译镜像可以直接启用硬件编码。

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.hardware.yml \
  up -d --build --wait
```

宿主机需要提供 `/dev/dri`。Intel 设备还需要容器内存在 `iHD_drv_video.so`；
官方预编译的 AMD64 镜像会安装 `intel-media-driver`。启动后在管理后台“系统设置”
中检测并开启硬件加速；设备、驱动、权限或编码器不可用时系统会拒绝保存。

瑞芯微设备可使用：

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.rockchip.yml \
  up -d --build --wait
```

宿主机需要提供 `/dev/mpp_service`，并且 FFmpeg 必须包含对应的 RK MPP 编码器。通用镜像不保证包含 `h264_rkmpp` 或 `hevc_rkmpp`。

## AI 配置与降级

AI 默认关闭，不影响扫描、转码、入库、点歌和播放。推荐在管理后台“系统设置 → AI 模型”中配置；也可以使用环境变量连接任意 OpenAI-compatible Chat Completions 服务：

```dotenv
KTV_AI_ENABLED=true
KTV_AI_BASE_URL=https://ai.example/v1
KTV_AI_API_KEY=你的密钥
KTV_AI_BULK_MODEL=your-model-id
KTV_AI_REASONING_MODEL=
KTV_AI_JSON_MODE=AUTO
KTV_AI_BULK_CONCURRENCY=2
KTV_AI_REASONING_CONCURRENCY=1
KTV_AI_AUTO_APPLY_CONFIDENCE=0.90
KTV_AI_ALLOW_PRIVATE_NETWORK=false
```

模型 ID 不做固定枚举限制；增强模型留空时复用批量模型。后台可以尝试获取模型列表并检测鉴权、Chat Completions 和 JSON 输出能力，不支持模型列表或 JSON Mode 的服务仍可手工配置和自动回退。

管理后台保存的 API Key 使用 AES-256-GCM 加密，接口只返回配置状态和尾号。主密钥优先读取 `KTV_CONFIG_MASTER_KEY`，否则生成到数据目录的 `secrets/config.key`。不要删除或丢失该文件，否则已保存的 API Key 无法解密。

公网 AI 服务默认要求 HTTPS，并会拒绝本机和内网地址，以避免把 API Key 发到意外目标。若使用可信局域网内的本地 AI 服务（例如 HTTP 地址），需要明确设置 `KTV_AI_ALLOW_PRIVATE_NETWORK=true`；不要把服务端口暴露到公网。

未配置 AI 或调用超时、限流、失败时，本地标签、文件名、歌词标签和目录解析仍可继续工作，不阻塞入库。自然语言主题歌单、歌手性别推断等没有等价本地判断能力的操作会提示先配置模型，不会伪造结果。

修改 `.env` 后重新部署：

```bash
docker compose up -d --build
```

密钥只应保存在本地 `.env` 或受控 Secret 中，不要提交到仓库。

## 日常运维

### 更新

```bash
git pull
docker compose up -d --build --wait
```

### 停止与启动

```bash
docker compose stop
docker compose start
```

移除容器但保留数据卷：

```bash
docker compose down
```

不要在需要保留数据时运行 `docker compose down -v`。

### 日志

```bash
docker compose logs -f ktv
docker compose logs -f db
```

### 备份与恢复

```bash
./scripts/backup.sh /volume1/backup/home-ktv
```

恢复指定备份：

```bash
./scripts/restore.sh \
  /volume1/backup/home-ktv/home-ktv-YYYYMMDD-HHMMSS.dump \
  --yes
```

数据库备份包含歌曲元数据、设置、歌单、队列和播放历史，不包含原始媒体文件。`source-music` 和 `music` 目录需要使用 NAS 自身的备份方案。应用启动时会自动执行 Flyway 升级；正式更新前仍建议先备份数据库和媒体目录。当前迁移保留历史源记录，不通过升级脚本批量删除业务数据。

## 本地开发

开发环境需要 Node.js 20+、JDK 21、JDK 17、Docker 和 Android SDK。

```bash
# PostgreSQL
docker compose -f docker-compose.dev.yml up -d

# 后端，JDK 21
cd backend
./mvnw spring-boot:run

# H5
cd h5
npm install
npm run dev

# Android TV，JDK 17
cd android-tv
./gradlew testDebugUnitTest assembleDebug
```

测试命令：

```bash
cd backend && ./mvnw test
cd h5 && npm test
cd android-tv && ./gradlew testDebugUnitTest
```

目录结构：

```text
backend/      Spring Boot 服务端、数据库、扫描、转码和实时控制
h5/           Vue 3 手机点歌端与管理后台
android-tv/   Kotlin Android TV 客户端
scripts/      备份、恢复和媒体辅助脚本
```

## 常见问题

### 手机扫码后打不开

- 确认手机和服务端在同一局域网。
- 确认二维码地址不是 Docker 的 `172.x` 容器地址。
- 在管理后台设置正确的“展示地址”。
- 检查 NAS 防火墙和 TCP 端口。

### TV 自动发现不到服务端

- 检查 UDP `18888` 是否放行。
- 确认路由器没有开启 AP 隔离或访客网络隔离。
- 在 TV 首次设置页手动输入 `<主机IP>:8080`。

### 原唱和伴奏相反

在手机遥控页点击“原唱和伴唱弄反了？”，系统会纠正当前文件的伴奏轨标记并保存。

### 视频无法播放或卡顿

- 在原始音乐管理中查看格式分析结果。
- 对不兼容文件执行转码。
- 检查 NAS CPU、磁盘和网络占用。
- Linux 主机可尝试启用 VAAPI 或 RK MPP 硬件转码。

### 歌词未显示

- 确认 LRC 与媒体文件基础名称完全相同。
- 检查时间标签格式是否为 `[mm:ss.xx]`。
- 修改歌词后重新扫描，系统支持同名侧车歌词更新。

## 已知限制

- 系统只适用于可信局域网，不应直接暴露到公网；管理接口有独立密码保护，但普通家庭遥控协议仍按局域网信任模型工作。
- AI 人声分离或声道相减生成的伴奏可能残留主唱，无法达到官方母带效果。
- 蓝牙麦克风延迟和音质取决于电视盒子固件，实时演唱优先使用 USB 或有线设备。
- 不同 KTV 视频的音轨顺序并不统一，首次导入后建议抽查。
- Android TV 自启动和后台保活可能需要盒子厂商的额外权限。

## 许可与媒体责任

项目代码采用 [MIT License](LICENSE)，允许自由使用、修改、分发和商业使用，但需保留许可证及版权声明。

请只导入和播放自己有权使用的媒体。Home KTV 不提供、下载或分发歌曲、MV 或伴奏资源。

问题反馈请附上 NAS 系统、Android TV 型号、Android 版本、媒体格式和相关日志，并删除 IP、密码、密钥等隐私信息。
