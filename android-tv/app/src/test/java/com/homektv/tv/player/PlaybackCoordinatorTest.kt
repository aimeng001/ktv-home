package com.homektv.tv.player

import com.homektv.tv.net.FileSource
import com.homektv.tv.net.FileSourceResolution
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.PlaybackDescriptor
import com.homektv.tv.net.PlaybackResolution
import com.homektv.tv.net.QueueSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {    @Test
    fun liveTranscodeUrlHelpersHandleMissingAndInvalidOffsets() {
        assertEquals("http://nas/api/stream/31?start=1.234", LiveTranscodeStreamUrl.withStart(
            "http://nas/api/stream/31",
            1_234L,
        ))
        assertEquals("http://nas/api/stream/31", LiveTranscodeStreamUrl.withStart(
            "http://nas/api/stream/31?start=12.000",
            -1L,
        ))

        assertFalse(LiveTranscodeStreamUrl.isLiveTranscode(null))
        assertFalse(LiveTranscodeStreamUrl.isLiveTranscode("http://nas/api/stream/31?transcode=1"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs(null))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://nas/api/stream/31"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://nas/api/stream/31?start=invalid"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://nas/api/stream/31?start=NaN"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://nas/api/stream/31?start=Infinity"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://nas/api/stream/31?start=-0.5"))
        assertEquals(1_250L, LiveTranscodeStreamUrl.startPositionMs("http://nas/api/stream/31?transcode=true&start=1.25"))
    }

    @Test
    fun sourceResolutionExceptionWithoutMessageReportsRetryableFallback() = runBlocking {
        val messages = mutableListOf<String>()
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 51L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        lateinit var coordinator: PlaybackCoordinator
        coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")

                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): com.homektv.tv.net.PlaybackResolution =
                    throw IllegalStateException(null as String?)

                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> error("a failed source must not start playback") },
            onMissingSource = { error("a retryable source failure is not an absent file") },
            onWaitingForSource = { _, error ->
                messages += error.message
                coordinator.invalidate()
            },
            retryDelay = {},
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(51L, 7L))
        ticket.job.join()

        assertEquals(listOf("文件源暂时不可用"), messages)
        assertFalse(coordinator.isCurrent(ticket))
        scope.cancel()
    }

    @Test
    fun readyDescriptorWithoutUrlUsesTheSourceFallbackUrl() = runBlocking {
        val sourceFile = FileSource(id = 71L, format = "mp4")
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 52L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var receivedUrl: String? = null
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")

                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean) =
                    com.homektv.tv.net.PlaybackResolution.Ready(
                        sourceFile,
                        com.homektv.tv.net.PlaybackDescriptor(
                            kind = "NATIVE",
                            status = "READY",
                            sourceFileId = sourceFile.id,
                        ),
                    )

                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, _, url ->
                assertEquals(sourceFile.id, file.id)
                receivedUrl = url
            },
            onMissingSource = { error("a ready source must not be reported missing") },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(52L, 7L))
        ticket.job.join()

        assertEquals("source://71", receivedUrl)
        scope.cancel()
    }

    @Test
    fun liveTranscodeSeekUrlReplacesPriorOffsetAndResetRemovesOffset() {
        assertEquals(
            "http://nas/api/stream/31?transcode=true&start=65.250",
            LiveTranscodeStreamUrl.withStart(
                "http://nas/api/stream/31?transcode=true&start=12.000",
                65_250L,
            ),
        )
        assertEquals(
            "http://nas/api/stream/31?transcode=true",
            LiveTranscodeStreamUrl.withStart(
                "http://nas/api/stream/31?transcode=true&start=12.000",
                0L,
            ),
        )
        assertEquals(12_000L, LiveTranscodeStreamUrl.startPositionMs("http://nas/stream?transcode=true&start=12.000"))
        assertTrue(LiveTranscodeStreamUrl.isLiveTranscode("http://nas/stream?transcode=true"))
    }

    @Test
    fun liveTranscodePositionMapsBetweenAbsoluteAndPipeTimeline() {
        assertEquals(91_125L, LiveTranscodePosition.absolutePositionMs(25_875L, 65_250L))
        assertEquals(25_875L, LiveTranscodePosition.pipePositionMs(91_125L, 65_250L))
        assertEquals(123_000L, LiveTranscodePosition.absolutePositionMs(3_000L, 120_000L))
        assertEquals(3_000L, LiveTranscodePosition.pipePositionMs(123_000L, 120_000L))
        assertEquals(0L, LiveTranscodePosition.pipePositionMs(10_000L, 65_250L))
    }

    @Test
    fun preparingPlaybackPollsUntilVariantReadyAndUsesVariantIdentity() = runBlocking {
        val events = mutableListOf<String>()
        val sourceFile = FileSource(id = 7L, format = "rmvb", audioTracks = 1, resolution = "720x480")
        val results = ArrayDeque<com.homektv.tv.net.PlaybackResolution>(listOf(
            com.homektv.tv.net.PlaybackResolution.Preparing(
                sourceFile,
                com.homektv.tv.net.PlaybackDescriptor(
                    kind = "TRANSCODE",
                    status = "PREPARING",
                    sourceFileId = 7L,
                    variantId = 88L,
                ),
            ),
            com.homektv.tv.net.PlaybackResolution.Ready(
                sourceFile,
                com.homektv.tv.net.PlaybackDescriptor(
                    kind = "TRANSCODE",
                    status = "READY",
                    sourceFileId = 7L,
                    variantId = 88L,
                    streamUrl = "http://nas/api/playback/stream/88",
                    audioTracks = 1,
                    audioLayout = AudioLayout.normalStereo(),
                ),
            ),
        ))
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 9L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")
                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean) = results.removeFirst()
                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, _, url -> events += "ready:${file.id}:${file.format}:$url" },
            onMissingSource = { events += "missing" },
            onPlaybackPreparing = { _, _ -> events += "preparing" },
            retryDelay = { events += "poll" },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(9L, 7L))
        ticket.job.join()

        assertEquals(listOf("preparing", "poll", "ready:88:mp4:http://nas/api/playback/stream/88"), events)
        scope.cancel()
    }

    @Test
    fun preparingPlaybackCallsOnPreparingOnlyOnceDuringRepeatedPolls() = runBlocking {
        val events = mutableListOf<String>()
        val sourceFile = FileSource(id = 7L, format = "rmvb", audioTracks = 1)
        val results = ArrayDeque<com.homektv.tv.net.PlaybackResolution>(listOf(
            com.homektv.tv.net.PlaybackResolution.Preparing(
                sourceFile,
                com.homektv.tv.net.PlaybackDescriptor(kind = "TRANSCODE", status = "PREPARING", sourceFileId = 7L, variantId = 88L),
            ),
            com.homektv.tv.net.PlaybackResolution.Preparing(
                sourceFile,
                com.homektv.tv.net.PlaybackDescriptor(kind = "TRANSCODE", status = "PREPARING", sourceFileId = 7L, variantId = 88L),
            ),
            com.homektv.tv.net.PlaybackResolution.Preparing(
                sourceFile,
                com.homektv.tv.net.PlaybackDescriptor(kind = "TRANSCODE", status = "PREPARING", sourceFileId = 7L, variantId = 88L),
            ),
            com.homektv.tv.net.PlaybackResolution.Ready(
                sourceFile,
                com.homektv.tv.net.PlaybackDescriptor(
                    kind = "TRANSCODE",
                    status = "READY",
                    sourceFileId = 7L,
                    variantId = 88L,
                    streamUrl = "http://nas/api/playback/stream/88",
                    audioTracks = 1,
                    audioLayout = AudioLayout.normalStereo(),
                ),
            ),
        ))
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 9L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")
                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean) = results.removeFirst()
                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, _, url -> events += "ready:${file.id}:$url" },
            onMissingSource = { events += "missing" },
            onPlaybackPreparing = { _, _ -> events += "preparing" },
            retryDelay = { events += "poll" },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(9L, 7L))
        ticket.job.join()

        assertEquals(1, events.count { it == "preparing" })
        assertEquals(3, events.count { it == "poll" })
        assertEquals("ready:88:http://nas/api/playback/stream/88", events.last())
        scope.cancel()
    }

    @Test
    fun preparingPlaybackDoesNotFailWhenServerStillPreparingAfterTwoMinutes() = runBlocking {
        val events = mutableListOf<String>()
        val sourceFile = FileSource(id = 7L, format = "rmvb", audioTracks = 1)
        var polls = 0
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 9L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")
                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): com.homektv.tv.net.PlaybackResolution {
                    polls++
                    return if (polls <= 121) {
                        com.homektv.tv.net.PlaybackResolution.Preparing(
                            sourceFile,
                            com.homektv.tv.net.PlaybackDescriptor(
                                kind = "TRANSCODE",
                                status = "PREPARING",
                                sourceFileId = 7L,
                                variantId = 88L,
                            ),
                        )
                    } else {
                        com.homektv.tv.net.PlaybackResolution.Ready(
                            sourceFile,
                            com.homektv.tv.net.PlaybackDescriptor(
                                kind = "TRANSCODE",
                                status = "READY",
                                sourceFileId = 7L,
                                variantId = 88L,
                                streamUrl = "http://nas/api/playback/stream/88",
                                audioTracks = 1,
                                audioLayout = AudioLayout.normalStereo(),
                            ),
                        )
                    }
                }
                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, _, _ -> events += "ready:${file.id}" },
            onMissingSource = { events += "missing" },
            onSourceExhausted = { _, error -> events += "exhausted:${error.code}" },
            retryDelay = {},
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(9L, 7L))
        ticket.job.join()

        assertEquals(122, polls)
        assertEquals(listOf("ready:88"), events)
        scope.cancel()
    }
    @Test
    fun exhaustedSourceProducesQueueScopedRecoverableErrorContext() {
        val token = PlaybackReplacementToken(
            generation = 3L,
            request = PlaybackReplacementRequest(queueId = 41L, songId = 7L),
        )

        val context = PlaybackSourceFailurePolicy.errorContext(token)

        assertEquals(41L, context.queueId)
        assertNull(context.fileId)
    }

    @Test
    fun replacementStopsOldOutputBeforeWaitingForFileSource() = runBlocking {
        val events = mutableListOf<String>()
        val sourceReady = CompletableDeferred<FileSourceResolution>()
        val request = PlaybackReplacementRequest(queueId = 41L, songId = 7L)
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 41L, song = null), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution = sourceReady.await()
                override fun streamUrl(fileId: Long): String = "http://nas/api/stream/$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = { events += "stop" },
            onFileReady = { _, _, _, _ -> events += "play" },
            onMissingSource = { events += "missing" },
        )

        val ticket = coordinator.replace(request)

        assertEquals(listOf("stop"), events)
        sourceReady.complete(FileSourceResolution.Ready(FileSource(id = 99L)))
        ticket.job.join()
        assertEquals(listOf("stop", "play"), events)
        scope.cancel()
    }

    @Test
    fun cancelledOlderSourceCannotCommitAfterNewQueueReplacement() = runBlocking {
        val events = mutableListOf<Long>()
        val first = CompletableDeferred<FileSourceResolution>()
        val second = CompletableDeferred<FileSourceResolution>()
        val desired = DesiredPlaybackState()
        desired.update(QueueSnapshot(playing = NowPlaying(queueId = 2L), state = "playing"))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    if (songId == 1L) first.await() else second.await()

                override fun streamUrl(fileId: Long): String = "stream://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { token, _, _, _ -> events += token.request.queueId ?: -1L },
            onMissingSource = {},
        )

        val firstTicket = coordinator.replace(PlaybackReplacementRequest(1L, 1L))
        val firstJob = firstTicket.job
        val secondTicket = coordinator.replace(PlaybackReplacementRequest(2L, 2L))
        val secondJob = secondTicket.job
        first.complete(FileSourceResolution.Ready(FileSource(id = 11L)))
        second.complete(FileSourceResolution.Ready(FileSource(id = 22L)))
        firstJob.join()
        secondJob.join()

        assertEquals(listOf(2L), events)
        assertTrue(coordinator.isCurrent(secondTicket.token))
        scope.cancel()
    }

    @Test
    fun liveTranscodeKeepsSourceIdentityAndCarriesRecoveryPositionAndPlaybackIntent() = runBlocking {
        val sourceFile = FileSource(id = 31L, format = "mkv", audioTracks = 2)
        val descriptor = com.homektv.tv.net.PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 31L,
            variantId = null,
            streamUrl = "http://nas/api/stream/31?transcode=true",
            audioTracks = 2,
            audioLayout = AudioLayout(layout = "DUAL_TRACK", accompanimentTrackIndex = 1),
        )
        val desired = DesiredPlaybackState().also {
            it.update(
                QueueSnapshot(
                    playing = NowPlaying(queueId = 41L),
                    state = "playing",
                    positionMs = 1_000L,
                    seekSequence = 8L,
                    volume = 37,
                    vocalMode = "accompaniment",
                ),
            )
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var received: Triple<FileSource, QueueSnapshot, String>? = null
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")
                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean) =
                    com.homektv.tv.net.PlaybackResolution.Ready(sourceFile, descriptor)
                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, snapshot, url -> received = Triple(file, snapshot, url) },
            onMissingSource = {},
        )

        val ticket = coordinator.replace(
            PlaybackReplacementRequest(
                queueId = 41L,
                songId = 7L,
                forceTranscode = true,
                recoveryPositionMs = 65_250L,
                recoverySeekSequence = 8L,
                recoveryPlayWhenReady = false,
                stateAtFailure = "playing",
            ),
        )
        ticket.job.join()

        val (file, snapshot, url) = requireNotNull(received)
        assertEquals(31L, file.id)
        assertEquals("mp4", file.format)
        assertEquals(2, file.audioTracks)
        assertEquals("DUAL_TRACK", file.audioLayout.layout)
        assertEquals(65_250L, snapshot.positionMs)
        assertEquals("paused", snapshot.state)
        assertEquals(37, snapshot.volume)
        assertEquals("accompaniment", snapshot.vocalMode)
        assertEquals("http://nas/api/stream/31?transcode=true&start=65.250", url)
        scope.cancel()
    }

    @Test
    fun newerSeekAndPauseArrivingDuringResolveOverrideTheFailureSnapshot() = runBlocking {
        val sourceFile = FileSource(id = 32L, format = "avi", audioTracks = 1)
        val descriptor = com.homektv.tv.net.PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 32L,
            streamUrl = "http://nas/api/stream/32?transcode=true",
        )
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 42L), state = "playing", positionMs = 10_000L, seekSequence = 3L))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var received: Triple<QueueSnapshot, String, FileSource>? = null
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")
                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): com.homektv.tv.net.PlaybackResolution {
                    desired.update(
                        QueueSnapshot(
                            playing = NowPlaying(queueId = 42L),
                            state = "paused",
                            positionMs = 91_125L,
                            seekSequence = 4L,
                        ),
                    )
                    return com.homektv.tv.net.PlaybackResolution.Ready(sourceFile, descriptor)
                }
                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, snapshot, url -> received = Triple(snapshot, url, file) },
            onMissingSource = {},
        )

        val ticket = coordinator.replace(
            PlaybackReplacementRequest(
                queueId = 42L,
                songId = 8L,
                forceTranscode = true,
                recoveryPositionMs = 65_000L,
                recoverySeekSequence = 3L,
                recoveryPlayWhenReady = true,
                stateAtFailure = "playing",
            ),
        )
        ticket.job.join()

        val (snapshot, url, file) = requireNotNull(received)
        assertEquals(91_125L, snapshot.positionMs)
        assertEquals("paused", snapshot.state)
        assertEquals("http://nas/api/stream/32?transcode=true&start=91.125", url)
        assertEquals(32L, file.id)
        scope.cancel()
    }

    @Test
    fun readySourceIsIgnoredWhenItsQueueItemIsNoLongerDesired() = runBlocking {
        val sourceFile = FileSource(id = 81L, format = "mp4")
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 99L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var started = false
        val coordinator = PlaybackCoordinator(
            source = readyPlaybackSource(sourceFile, PlaybackDescriptor(kind = "NATIVE", status = "READY")),
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> started = true },
            onMissingSource = { error("a ready source is not an absent source") },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(queueId = 81L, songId = 7L))
        ticket.job.join()

        assertFalse(started)
        assertTrue(coordinator.isCurrent(ticket))
        scope.cancel()
    }

    @Test
    fun recoveryRestoresPlayIntentWithoutOverwritingANewerSeek() = runBlocking {
        val sourceFile = FileSource(id = 82L, format = "avi")
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 82L,
            streamUrl = "http://nas/api/stream/82?transcode=true",
        )
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(
                playing = NowPlaying(queueId = 82L),
                state = "playing",
                positionMs = 91_125L,
                seekSequence = 4L,
            ))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var received: Pair<QueueSnapshot, String>? = null
        val coordinator = PlaybackCoordinator(
            source = readyPlaybackSource(sourceFile, descriptor),
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, url -> received = snapshot to url },
            onMissingSource = { error("a ready source is not an absent source") },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(
            queueId = 82L,
            songId = 7L,
            forceTranscode = true,
            recoveryPositionMs = 65_000L,
            recoverySeekSequence = 3L,
            recoveryPlayWhenReady = false,
            stateAtFailure = "playing",
        ))
        ticket.job.join()

        val (snapshot, url) = requireNotNull(received)
        assertEquals(91_125L, snapshot.positionMs)
        assertEquals("paused", snapshot.state)
        assertEquals("http://nas/api/stream/82?transcode=true&start=91.125", url)
        scope.cancel()
    }

    @Test
    fun absentRecoveryValuesLeaveTheLatestLiveTranscodeSnapshotUnchanged() = runBlocking {
        val sourceFile = FileSource(id = 83L, format = "avi")
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 83L,
            streamUrl = "http://nas/api/stream/83?transcode=true",
        )
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(
                playing = NowPlaying(queueId = 83L),
                state = "paused",
                positionMs = 12_000L,
                seekSequence = 4L,
            ))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var received: Pair<QueueSnapshot, String>? = null
        val coordinator = PlaybackCoordinator(
            source = readyPlaybackSource(sourceFile, descriptor),
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, url -> received = snapshot to url },
            onMissingSource = { error("a ready source is not an absent source") },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(
            queueId = 83L,
            songId = 7L,
            forceTranscode = true,
        ))
        ticket.job.join()

        val (snapshot, url) = requireNotNull(received)
        assertEquals(12_000L, snapshot.positionMs)
        assertEquals("paused", snapshot.state)
        assertEquals("http://nas/api/stream/83?transcode=true&start=12.000", url)
        scope.cancel()
    }

    @Test
    fun automaticLegacyPipeAppliesRecoveryPositionWithoutForceFlag() = runBlocking {
        val sourceFile = FileSource(id = 84L, format = "avi")
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 84L,
            streamUrl = "http://nas/api/stream/84?transcode=true",
        )
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(
                playing = NowPlaying(queueId = 84L),
                state = "playing",
                positionMs = 2_000L,
                seekSequence = 1L,
            ))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var received: Pair<QueueSnapshot, String>? = null
        val coordinator = PlaybackCoordinator(
            source = readyPlaybackSource(sourceFile, descriptor),
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, url -> received = snapshot to url },
            onMissingSource = { error("a ready source is not an absent source") },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(
            queueId = 84L,
            songId = 7L,
            forceTranscode = false,
            recoveryPositionMs = 60_000L,
            recoverySeekSequence = 1L,
            recoveryPlayWhenReady = false,
            stateAtFailure = "playing",
        ))
        ticket.job.join()

        val (snapshot, url) = requireNotNull(received)
        assertEquals(84L, snapshot.playing?.queueId)
        assertEquals(60_000L, snapshot.positionMs)
        assertEquals("paused", snapshot.state)
        assertEquals("http://nas/api/stream/84?transcode=true&start=60.000", url)
        scope.cancel()
    }

    @Test
    fun retryableFailureNeverCallsMissingSource() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var coordinator: PlaybackCoordinator
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 41L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    FileSourceResolution.Retryable(
                        KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline"),
                    )

                override fun streamUrl(fileId: Long): String = "stream://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> events += "ready" },
            onMissingSource = { events += "missing" },
            onWaitingForSource = { _, _ -> events += "waiting" },
            retryDelay = {
                coordinator.invalidate()
            },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(41L, 7L))
        ticket.job.join()

        assertEquals(listOf("waiting"), events)
        scope.cancel()
    }

    @Test
    fun retryEventuallyCommitsReadySource() = runBlocking {
        val events = mutableListOf<String>()
        val results = ArrayDeque<FileSourceResolution>(listOf(
            FileSourceResolution.Retryable(
                KtvApiError(KtvApiErrorKind.HTTP, "HTTP_500", "server", status = 500),
            ),
            FileSourceResolution.Ready(FileSource(id = 22L)),
        ))
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 2L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution = results.removeFirst()
                override fun streamUrl(fileId: Long): String = "stream://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, _, _ -> events += "ready:${file.id}" },
            onMissingSource = { events += "missing" },
            onWaitingForSource = { _, _ -> events += "waiting" },
            retryDelay = { delay -> events += "delay:$delay" },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(2L, 2L))
        ticket.job.join()

        assertEquals(listOf("waiting", "delay:500", "ready:22"), events)
        scope.cancel()
    }

    @Test
    fun absentSourceCallsMissingExactlyOnce() = runBlocking {
        var missing = 0
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 3L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    FileSourceResolution.Absent(com.homektv.tv.net.AbsentReason.NO_VALID_FILE)

                override fun streamUrl(fileId: Long): String = "stream://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> error("must not play") },
            onMissingSource = { missing++ },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(3L, 3L))
        ticket.job.join()

        assertEquals(1, missing)
        scope.cancel()
    }

    @Test
    fun fatalResolutionReportsSourceFailureWithoutSkippingQueue() = runBlocking {
        val events = mutableListOf<String>()
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 41L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    FileSourceResolution.Fatal(
                        KtvApiError(KtvApiErrorKind.DECODE, "SONG_DETAIL_DECODE", "malformed detail"),
                    )
                override fun streamUrl(fileId: Long): String = "stream://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> events += "ready" },
            onMissingSource = { events += "missing" },
            onSourceFailure = { _, error -> events += "failure:${error.code}" },
            onWaitingForSource = { _, _ -> events += "waiting" },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(41L, 7L))
        ticket.job.join()

        assertEquals(listOf("failure:SONG_DETAIL_DECODE"), events)
        scope.cancel()
    }

    @Test
    fun retryableExhaustionWaitsWithoutSkippingQueue() = runBlocking {
        val events = mutableListOf<String>()
        val desired = DesiredPlaybackState().also {
            it.update(QueueSnapshot(playing = NowPlaying(queueId = 41L), state = "playing"))
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    FileSourceResolution.Retryable(
                        KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline"),
                    )
                override fun streamUrl(fileId: Long): String = "stream://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> events += "ready" },
            onMissingSource = { events += "missing" },
            onSourceExhausted = { _, error -> events += "exhausted:${error.code}" },
            onWaitingForSource = { _, _ -> events += "waiting" },
            retryDelay = { delay -> events += "delay:$delay" },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(41L, 7L))
        ticket.job.join()

        assertEquals(
            listOf("waiting", "delay:500", "delay:1500", "delay:3000", "delay:10000", "exhausted:NETWORK_ERROR"),
            events,
        )
        scope.cancel()
    }

    private fun readyPlaybackSource(file: FileSource, descriptor: PlaybackDescriptor) = object : PlaybackSource {
        override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
            FileSourceResolution.Ready(file)

        override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): PlaybackResolution =
            PlaybackResolution.Ready(file, descriptor)

        override fun streamUrl(fileId: Long): String = "source://$fileId"
    }
}


