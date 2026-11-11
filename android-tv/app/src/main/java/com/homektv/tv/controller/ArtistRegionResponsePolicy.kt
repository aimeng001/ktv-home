package com.homektv.tv.controller

/** Prevents a newer TV from treating an older server's ignored filter as applied. */
object ArtistRegionResponsePolicy {
    fun accepts(requestedRegion: String, appliedRegion: String?, referenceAvailable: Boolean): Boolean =
        requestedRegion.isBlank() ||
            (referenceAvailable && appliedRegion == requestedRegion)

    fun unavailableMessage(referenceAvailable: Boolean): String = if (referenceAvailable) {
        "当前点歌服务未应用地区筛选，已停止显示未过滤结果"
    } else {
        "歌手地区参考资料尚未载入"
    }
}
