# Android 控制器 UI 决策记录

状态：已执行的 V1 技术决策（2026-09-11）

## 决策

播放器继续使用现有 XML + ViewBinding + Media3。控制器使用独立的原生 Fragment：`ControllerActivity` 只负责宿主、方向和模式切换，`ControllerFragment` 通过 `fragment_controller.xml` + ViewBinding 绑定顶层容器，列表行通过 `item_controller_row.xml` + ViewBinding 交给 `ControllerPanelRowBinder`，不引入 WebView，也不把点歌页面堆进 `MainActivity`。

## 原因

- 当前播放器的 XML、焦点和横屏行为已经是稳定回归保护区；不因点歌功能迁移播放器。
- 工程尚未建立 Compose 的主题、TV DPAD 焦点、导航和进程恢复基线；V1 直接引入 Compose 会扩大构建和验收面。
- 控制器 UI 与播放器 Activity 已分离，手机/平板可以自适应方向，组合模式可以在不重建播放器的情况下打开控制器。
- ViewModel/Repository/typed API 已与 UI 解耦，列表渲染已迁移到 RecyclerView/独立 Adapter 和 Binder；顶层 Activity 已拆为 Fragment，后续如需评估 Compose 必须另立样片和性能验收。

## 约束与后续 Gate

当前 V1 控制器已实现手机竖屏单面板导航、平板双栏、TV/盒子横屏、自适应方向、动态 RecyclerView 列表、DPAD 焦点链、旋转/进程状态恢复和基础无障碍描述；`ControllerActivityInstrumentedTest` 已覆盖第 51 条稳定 ID 和控制器骨架。列表只提交已加载的有界数据，RecyclerView 使用固定视口和嵌套滚动，不能把服务端分页误当成渲染截断。真实手机、平板、电视盒子上的焦点、TalkBack、分屏、进程重建、性能和大曲库滚动仍须现场执行，当前无 ADB 设备因此不能标记通过。
