package com.homektv.tv.controller

/** UI guard for queue mutations; the server remains the final authorization boundary. */
object QueueDrawerActionPolicy {
    fun isEnabled(action: String, pendingActions: Set<ActionKey>): Boolean =
        ActionKey(action) !in pendingActions
}
