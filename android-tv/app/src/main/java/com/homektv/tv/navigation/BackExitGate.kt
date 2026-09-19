package com.homektv.tv.navigation

enum class BackExitDecision {
    CONSUMED_AND_PROMPTED, // 拦截并提示“再次按返回键退出应用”
    EXIT_APP,              // 确认退出应用
    IGNORED,               // 不拦截（交给底层子页面处理）
}

/**
 * 全局统一双击返回退出守卫（纯逻辑状态机）。
 * 解决两项关键问题：
 * 1. PLAYER 模式下机顶盒遥控器误触返回键直接杀死进程（需双击退出防误触）；
 * 2. COMBINED 模式下首页死循环或在操作菜单/切Tab时意外退出（增加交互打断重置 reset）。
 */
class BackExitGate(
    private val timeoutMs: Long = 2000L,
) {
    private var lastBackMs: Long = 0L

    fun onBack(nowMs: Long, isAtTopLevel: Boolean): BackExitDecision {
        if (!isAtTopLevel) {
            reset()
            return BackExitDecision.IGNORED
        }
        if (lastBackMs > 0L && nowMs >= lastBackMs && (nowMs - lastBackMs) <= timeoutMs) {
            reset()
            return BackExitDecision.EXIT_APP
        }
        lastBackMs = nowMs
        return BackExitDecision.CONSUMED_AND_PROMPTED
    }

    fun reset() {
        lastBackMs = 0L
    }
}
