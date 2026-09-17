package com.homektv.tv.player

/** Latest user/server playback intent used when a transient retry fires. */
internal class PlaybackRetryState {
    var playWhenReady: Boolean = false
        private set
    var isReplacementActive: Boolean = false
        private set

    /**
     * 播放中最后一次被观测到的位置（进度采样回调持续写入）。
     *
     * <p>不能只依赖瞬时错误发生瞬间播放器自报的位置：播放器在出错时可能已把位置清零，
     * 那样重试就会退回 [start] / [seekTo] 留下的固定目标。尤其是"重唱"会把目标设为 0，
     * 一旦出错整首歌都会被打回片头。这里保存实际播放进度作为可靠的续播点。
     */
    private var lastObservedPositionMs = 0L

    fun start(initialPositionMs: Long, playWhenReady: Boolean) {
        isReplacementActive = false
        this.playWhenReady = playWhenReady
        // 初始快照位置既是装载位置，也是尚未产生播放进度时的续播兜底。
        lastObservedPositionMs = initialPositionMs.coerceAtLeast(0L)
    }

    fun pause() {
        playWhenReady = false
    }

    fun resume() {
        playWhenReady = true
    }

    fun setPlayWhenReady(value: Boolean) {
        if (value) resume() else pause()
    }

    fun seekTo(positionMs: Long) {
        // 显式跳转后旧进度立即失效，续播点对齐到跳转目标。
        lastObservedPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun beginReplacement() {
        isReplacementActive = true
        playWhenReady = false
        lastObservedPositionMs = 0L
    }

    /**
     * 瞬时错误后的续播位置：取"失败位置"与"已观测到的播放进度"中更靠后者。
     *
     * <p>正常播放时两者一致；跳转后已经播过去时取更靠后的进度，避免被打回跳转点；
     * 失败位置不可用（归零）时回退到已观测进度，避免被打回片头。
     */
    fun positionForRetry(failurePositionMs: Long): Long =
        maxOf(failurePositionMs.coerceAtLeast(0L), lastObservedPositionMs)

    /** 播放中由进度采样回调持续更新，用于瞬时错误后可靠续播。 */
    fun onPositionSampled(positionMs: Long) {
        lastObservedPositionMs = positionMs.coerceAtLeast(0L)
    }
}
