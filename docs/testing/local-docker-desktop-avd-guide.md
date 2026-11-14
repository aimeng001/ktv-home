# 本机 Docker Desktop 与 Android 模拟器联调指南

> 本指南整合了本机 Docker 服务检查、Android AVD 静默启动、APK 构建安装与端到端验收规程。所有命令在管理员 PowerShell 内执行，环境变量仅在当前进程生效。

## 1. 原则与安全边界

- **服务部署**：用户负责部署 Docker Desktop 服务端。未经明确授权，不执行 `compose up`、构建、拉取、重启或容器替换；禁止切换到远程/NAS context 或操作持久数据卷。
- **曲库只读**：严禁对 NAS 曲库执行任何写操作（删除/移动/原地转码等）；服务端必须维持内存管道（`pipe:1`）0 磁盘缓存。
- **单机串行**：同一时间仅允许运行一台 AVD；测试全流程优先使用 ADB，不启动前台窗口。

## 2. Docker Desktop 服务验证

1. 确认 context 必须为 `desktop-linux`：
   ```powershell
   if ((docker context show).Trim() -ne 'desktop-linux') { throw 'Unexpected Docker context' }
   docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
   ```
2. 校验健康与就绪接口（默认端口 18080 或 8080）：
   ```powershell
   Invoke-RestMethod "http://127.0.0.1:<宿主机端口>/api/health"
   Invoke-RestMethod "http://127.0.0.1:<宿主机端口>/api/ready"
   ```
3. 仅在用户明确授权清理旧版本时，可按精确 ID 删除确认过期且未被引用的旧应用镜像，不使用 prune，不删除数据卷与数据库。

## 3. Android 模拟器静默启动规范（强制 Headless）

**今后所有自动化测试与 UI 验收均必须静默启动模拟器，严禁弹出 GUI 窗口干扰主屏操作。**

### 3.1 环境变量隔离与静默启动
```powershell
$Sdk = 'K:\Android'
$UserDir = 'D:\mimocode\ktv-home\android-tv\app\build\outputs\ui-evidence\task7-tv-remote-resolution-20261105'
$AvdName = 'KTV_TASK7_TV_1080P_X86' # 按需指定：如 TV 1080p 或 Phone 1080p

$env:ANDROID_SDK_ROOT      = $Sdk
$env:ANDROID_HOME          = $Sdk
$env:ANDROID_AVD_HOME      = Join-Path $UserDir 'android-user\avd'
$env:ANDROID_USER_HOME     = Join-Path $UserDir 'android-user'
$env:ANDROID_EMULATOR_HOME = Join-Path $UserDir 'android-user'

# 强制静默参数：-no-window（无头无窗口）、-no-audio（禁用音频占用）
& "$Sdk\emulator\emulator.exe" -avd $AvdName -no-window -no-audio
```

### 3.2 常用预置 AVD 清单
优先复用已有 AVD（在 `K:\Android\avd` 或 `$UserDir\android-user\avd`）：
- **TV 1080p**：`KTV_TASK7_TV_1080P_X86` / `KTV_API34_TV_X86`（1920×1080 @ 320 dpi）
- **Phone 1080p**：`KTV_API34_PHONE_PIXEL6`（1080×2400 @ 420 dpi）
- **Phone 2K/QHD**：`KTV_UI_AUDIT_PIXELXL_API34_X86_64`（1440×2560 @ 560 dpi）

### 3.3 就绪判断与安全退出
- **就绪判定**：`adb -s <serial> shell getprop sys.boot_completed` 输出 `1`。
- **安全退出**：测试完成后执行 `adb -s <serial> emu kill` 优雅退出模拟器。

### 3.4 自动截图脚本的隔离要求
- `参考/capture_tab_variants.py` 只允许在没有其他 ADB emulator 设备或 emulator 进程时启动；它会从指定 TV AVD 模板复制配置到本轮临时 AVD，并使用独立的 Android 用户目录和空白 userdata。
- `--avd-template` 与 `--apk` 的 ABI 必须匹配；默认使用 x86 TV 模板和 x86 split APK。替代架构只在本机 emulator 明确支持时使用，不能通过修改 BIOS、Hyper-V 或主机安全配置绕过启动失败。
- Windows 受限进程沙箱可能检测到 Hyper-V，却无法让 emulator 实际使用 WHPX。若 `emulator -accel-check` 返回非 0 状态（本机曾返回 6），不要据此改 BIOS、Hyper-V 或安全设置；只有在获准的沙箱外进程中重跑同一个隔离截图脚本，并确认日志出现 `Windows Hypervisor Platform accelerator is operational`，才继续验收。`--software-cpu-emulation` 是慢速诊断路径，不保证当前 AVD/宿主机能够启动；x86_64 主机不能用 ARM64 guest 替代 x86 TV AVD。
- 脚本固定使用 `-no-window -no-audio`，为每轮选择独立 serial，并在 `参考/acceptance_screenshots_v50_runs/<run-id>/` 建立独立输出目录；成功时保存截图和 manifest，失败时保留 failure.json 与已采集证据。历史 `参考/acceptance_screenshots_v50/` 不会被覆盖。
- 启动、安装、页面状态、截图或清理失败均返回非零，并保留本轮诊断目录。清理只针对本轮创建的 serial / 进程句柄；不得绕过预检复用或关闭用户已有模拟器。

## 4. Gradle 构建与 APK 安装

统一使用 JDK 17 与项目 Gradle Wrapper：
```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'D:\mimocode\ktv-home\android-tv'

# 按 AVD 架构构建 Debug APK（x86 或 x86_64）
.\gradlew.bat --no-daemon :app:assembleDebug "-Pktv.android.emulatorAbis=x86"

# 覆盖安装并显式启动 MainActivity
$Adb = 'K:\Android\platform-tools\adb.exe'
& $Adb -s <serial> install -r app\build\outputs\apk\debug\app-x86-debug.apk
& $Adb -s <serial> shell am start -n com.homektv.tv.debug/com.homektv.tv.ui.MainActivity -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER
```

## 5. 业务连通与无头 UI 验收

1. **服务端地址配置**：AVD 访问宿主机服务必须使用 `10.0.2.2:<宿主机端口>`（禁止用 127.0.0.1 或容器名）。可通过首选项注入或设置页写入：
   ```powershell
   & $Adb -s <serial> shell 'mkdir -p /data/data/com.homektv.tv.debug/shared_prefs && echo "<map><string name=\"server_host\">10.0.2.2:18080</string></map>" > /data/data/com.homektv.tv.debug/shared_prefs/ktv_tv.xml'
   ```
2. **模拟遥控器与触摸交互**：
   - 方向导航：`adb shell input keyevent 19`(上) / `20`(下) / `21`(左) / `22`(右) / `23`(确定) / `4`(返回)
   - 触摸点击：`adb shell input tap <x> <y>`
3. **真实渲染画面抓取**：
   从显存 Framebuffer 导出真实渲染画面，不得仅凭静态 XML 判断：
   ```powershell
   & $Adb -s <serial> exec-out screencap -p > output-evidence.png
   ```
4. **真实业务请求交叉验证**：
   必须通过 App UI 发起至少一次真实目录搜索、点歌出队或播控交互，以真实服务端响应和队列状态变化为通过判据。
