package com.homektv.tv.player

import com.homektv.tv.net.AbsentReason
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.FileSource
import com.homektv.tv.net.FileSourceResolution
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

class PlaybackCoordinatorBranchCoverageTest {
    @Test
    fun defaultSourceBridgeMapsEveryResolutionAndBothVocalTrackSources() = runBlocking {
        val file = FileSource(
            id = 17L,
            format = "mkv",
            audioTracks = 2,
            vocalTrackIndex = 1,
            audioLayout = AudioLayout(layout = "DUAL_TRACK", accompanimentTrackIndex = 0),
        )
        val ready = sourceFor(FileSourceResolution.Ready(file)).resolvePlayback(9L) as PlaybackResolution.Ready
        assertEquals(file, ready.source)
        assertEquals("NATIVE", ready.descriptor.kind)
        assertEquals("READY", ready.descriptor.status)
        assertEquals(17L, ready.descriptor.sourceFileId)
        assertEquals("source://17", ready.descriptor.streamUrl)
        assertEquals(2, ready.descriptor.audioTracks)
        assertEquals(0, ready.descriptor.vocalTrackIndex)
        assertEquals(file.audioLayout, ready.descriptor.audioLayout)

        val fallbackFile = file.copy(audioLayout = AudioLayout(layout = "DUAL_TRACK"))
        val fallback = sourceFor(FileSourceResolution.Ready(fallbackFile))
            .resolvePlayback(9L) as PlaybackResolution.Ready
        assertEquals(1, fallback.descriptor.vocalTrackIndex)

        assertEquals(
            PlaybackResolution.Absent(AbsentReason.NO_VALID_FILE),
            sourceFor(FileSourceResolution.Absent(AbsentReason.NO_VALID_FILE)).resolvePlayback(9L),
        )
        val error = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline")
        assertEquals(
            PlaybackResolution.Fatal(error),
            sourceFor(FileSourceResolution.Fatal(error)).resolvePlayback(9L),
        )
        assertEquals(
            PlaybackResolution.ConfigurationFailure(error),
            sourceFor(FileSourceResolution.ConfigurationFailure(error)).resolvePlayback(9L),
        )
        assertEquals(
            PlaybackResolution.Retryable(error),
            sourceFor(FileSourceResolution.Retryable(error)).resolvePlayback(9L),
        )
    }

    @Test
    fun resolverExceptionPreservesItsMessageInTheRetryableCallback() = runBlocking {
        val desired = desiredState(1L)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val messages = mutableListOf<String>()
        lateinit var coordinator: PlaybackCoordinator
        coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                    error("resolvePlayback should be used")

                override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): PlaybackResolution =
                    throw IllegalStateException("temporary resolver message")

                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desired,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> error("failed resolution must not start playback") },
            onMissingSource = { error("failed resolution is not an absent file") },
            onWaitingForSource = { _, error ->
                messages += error.message
                coordinator.invalidate()
            },
            retryDelay = {},
        )

        coordinator.replace(PlaybackReplacementRequest(1L, 7L)).job.join()

        assertEquals(listOf("temporary resolver message"), messages)
        scope.cancel()
    }

    @Test
    fun failedAndConfigurationResolutionsUseTheSourceFailureCallback() = runBlocking {
        val error = KtvApiError(KtvApiErrorKind.HTTP, "SOURCE_REJECTED", "source rejected", status = 403)
        val results = listOf(
            PlaybackResolution.Failed(
                FileSource(id = 7L),
                PlaybackDescriptor(kind = "TRANSCODE", status = "FAILED", sourceFileId = 7L),
                error,
            ),
            PlaybackResolution.ConfigurationFailure(error),
        )

        results.forEachIndexed { index, result ->
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val reported = mutableListOf<KtvApiError>()
            val coordinator = PlaybackCoordinator(
                source = sourceFor(result),
                desiredState = desiredState(index + 1L),
                scope = scope,
                onBeginReplacement = {},
                onFileReady = { _, _, _, _ -> error("failure must not start playback") },
                onMissingSource = { error("failure must not be reported as missing") },
                onSourceFailure = { _, sourceError -> reported += sourceError },
                onWaitingForSource = { _, _ -> error("terminal failure must not retry") },
                onSourceExhausted = { _, _ -> error("terminal failure must not exhaust retries") },
            )

            coordinator.replace(PlaybackReplacementRequest(index + 1L, 7L)).job.join()

            assertEquals(listOf(error), reported)
            scope.cancel()
        }
    }

    @Test
    fun transcodeWithoutVariantIdKeepsTheOriginalFileIdentity() = runBlocking {
        val sourceFile = FileSource(id = 31L, format = "avi", ready = true)
        val descriptor = PlaybackDescriptor(
            kind = "TRANSCODE",
            status = "READY",
            sourceFileId = sourceFile.id,
            streamUrl = "variant://not-created",
            variantId = null,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var observed: Pair<FileSource, String>? = null
        val coordinator = PlaybackCoordinator(
            source = sourceFor(PlaybackResolution.Ready(sourceFile, descriptor)),
            desiredState = desiredState(3L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, file, _, url -> observed = file to url },
            onMissingSource = { error("ready file must not be reported missing") },
        )

        coordinator.replace(PlaybackReplacementRequest(3L, 8L)).job.join()

        assertEquals(sourceFile to "variant://not-created", observed)
        scope.cancel()
    }

    @Test
    fun zeroPositionLiveTranscodeDoesNotAppendAStartParameter() = runBlocking {
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 41L,
            streamUrl = "http://server/api/stream/41?transcode=true&token=local",
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var observed: Pair<QueueSnapshot, String>? = null
        val coordinator = PlaybackCoordinator(
            source = sourceFor(PlaybackResolution.Ready(FileSource(id = 41L), descriptor)),
            desiredState = desiredState(4L, positionMs = 0L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, url -> observed = snapshot to url },
            onMissingSource = { error("ready source must not be reported missing") },
        )

        coordinator.replace(
            PlaybackReplacementRequest(queueId = 4L, songId = 9L, forceTranscode = true),
        ).job.join()

        assertEquals(0L, requireNotNull(observed).first.positionMs)
        assertEquals(descriptor.streamUrl, requireNotNull(observed).second)
        scope.cancel()
    }

    @Test
    fun recoveryWithoutFailureSequenceUsesTheCurrentSnapshotAndRecoveryIntent() = runBlocking {
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 51L,
            streamUrl = "http://server/api/stream/51?transcode=true",
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var observed: QueueSnapshot? = null
        val coordinator = PlaybackCoordinator(
            source = sourceFor(PlaybackResolution.Ready(FileSource(id = 51L), descriptor)),
            desiredState = desiredState(5L, state = "playing", positionMs = 3_000L, seekSequence = 8L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, _ -> observed = snapshot },
            onMissingSource = { error("ready source must not be reported missing") },
        )

        coordinator.replace(
            PlaybackReplacementRequest(
                queueId = 5L,
                songId = 9L,
                forceTranscode = true,
                recoveryPositionMs = 7_000L,
                recoverySeekSequence = null,
                recoveryPlayWhenReady = false,
                stateAtFailure = null,
            ),
        ).job.join()

        assertEquals(7_000L, requireNotNull(observed).positionMs)
        assertEquals("paused", requireNotNull(observed).state)
        scope.cancel()
    }

    @Test
    fun recoveryDoesNotRestoreValuesWhenTheSeekAndPlaybackStateHaveChanged() = runBlocking {
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 52L,
            streamUrl = "http://server/api/stream/52?transcode=true",
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var observed: QueueSnapshot? = null
        val coordinator = PlaybackCoordinator(
            source = sourceFor(PlaybackResolution.Ready(FileSource(id = 52L), descriptor)),
            desiredState = desiredState(6L, state = "paused", positionMs = 12_000L, seekSequence = 9L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, _ -> observed = snapshot },
            onMissingSource = { error("ready source must not be reported missing") },
        )

        coordinator.replace(
            PlaybackReplacementRequest(
                queueId = 6L,
                songId = 9L,
                forceTranscode = true,
                recoverySeekSequence = 8L,
                recoveryPlayWhenReady = null,
                stateAtFailure = "playing",
            ),
        ).job.join()

        assertEquals(12_000L, requireNotNull(observed).positionMs)
        assertEquals("paused", requireNotNull(observed).state)
        scope.cancel()
    }

    @Test
    fun recoveryRestoresPlayingIntentWhenTheLatestFailureSnapshotStillMatches() = runBlocking {
        val descriptor = PlaybackDescriptor(
            kind = "LIVE_TRANSCODE",
            status = "READY",
            sourceFileId = 53L,
            streamUrl = "http://server/api/stream/53?transcode=true",
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var observed: QueueSnapshot? = null
        val coordinator = PlaybackCoordinator(
            source = sourceFor(PlaybackResolution.Ready(FileSource(id = 53L), descriptor)),
            desiredState = desiredState(8L, state = "paused", positionMs = 3_000L, seekSequence = 1_024L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, _ -> observed = snapshot },
            onMissingSource = { error("ready source must not be reported missing") },
        )

        coordinator.replace(
            PlaybackReplacementRequest(
                queueId = 8L,
                songId = 9L,
                forceTranscode = true,
                recoveryPositionMs = 7_000L,
                recoverySeekSequence = 1_024L,
                recoveryPlayWhenReady = true,
                stateAtFailure = "PAUSED",
            ),
        ).job.join()

        assertEquals(7_000L, requireNotNull(observed).positionMs)
        assertEquals("playing", requireNotNull(observed).state)
        scope.cancel()
    }

    @Test
    fun invalidationCancelsPendingWorkAndCurrentnessChecksBothTokenFields() = runBlocking {
        val pendingSource = CompletableDeferred<FileSourceResolution>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val coordinator = PlaybackCoordinator(
            source = object : PlaybackSource {
                override suspend fun resolveFileSource(songId: Long): FileSourceResolution = pendingSource.await()
                override fun streamUrl(fileId: Long): String = "source://$fileId"
            },
            desiredState = desiredState(7L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> error("invalidated work must not start playback") },
            onMissingSource = { error("invalidated work must not report a missing file") },
        )
        val ticket = coordinator.replace(PlaybackReplacementRequest(7L, 10L))

        assertTrue(coordinator.isCurrent(ticket))
        assertFalse(coordinator.isCurrent(ticket.token.copy(generation = ticket.token.generation + 1)))
        assertFalse(coordinator.isCurrent(ticket.token.copy(request = ticket.token.request.copy(songId = 11L))))

        coordinator.invalidate()
        ticket.job.join()

        assertTrue(ticket.job.isCancelled)
        assertFalse(coordinator.isCurrent(ticket))
        scope.cancel()
    }

    @Test
    fun invalidationAfterSnapshotReadPreventsTheReadyResultFromCommitting() = runBlocking {
        val snapshot = QueueSnapshot(playing = NowPlaying(queueId = 9L), state = "playing")
        val scope = CoroutineScope(Dispatchers.Unconfined)
        lateinit var coordinator: PlaybackCoordinator
        var playbackStarted = false
        val snapshotProvider = object : DesiredPlaybackSnapshotProvider {
            override fun forQueue(queueId: Long?): QueueSnapshot? {
                coordinator.invalidate()
                return snapshot.takeIf { it.playing?.queueId == queueId }
            }
        }
        coordinator = PlaybackCoordinator(
            source = sourceFor(
                PlaybackResolution.Ready(
                    FileSource(id = 91L),
                    PlaybackDescriptor(kind = "NATIVE", status = "READY", sourceFileId = 91L),
                ),
            ),
            desiredState = snapshotProvider,
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, _, _ -> playbackStarted = true },
            onMissingSource = { error("ready source must not be reported missing") },
        )

        val ticket = coordinator.replace(PlaybackReplacementRequest(9L, 9L))
        ticket.job.join()

        assertFalse(playbackStarted)
        assertFalse(coordinator.isCurrent(ticket))
        scope.cancel()
    }

    @Test
    fun reentrantReplacementCannotBeOverwrittenByTheOlderResolvingJob() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var replacementStarted = false
        var replacementTicket: PlaybackReplacementTicket? = null
        lateinit var coordinator: PlaybackCoordinator
        val readyQueueIds = mutableListOf<Long?>()
        val coordinatorSource = object : PlaybackSource {
            override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
                error("resolvePlayback should be used")

            override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): PlaybackResolution {
                if (songId == 1L && !replacementStarted) {
                    replacementStarted = true
                    replacementTicket = coordinator.replace(PlaybackReplacementRequest(2L, 2L))
                }
                return PlaybackResolution.Ready(
                    FileSource(id = songId),
                    PlaybackDescriptor(kind = "NATIVE", status = "READY", sourceFileId = songId),
                )
            }

            override fun streamUrl(fileId: Long): String = "source://$fileId"
        }
        coordinator = PlaybackCoordinator(
            source = coordinatorSource,
            desiredState = desiredState(2L),
            scope = scope,
            onBeginReplacement = {},
            onFileReady = { _, _, snapshot, _ -> readyQueueIds += snapshot.playing?.queueId },
            onMissingSource = { error("ready source must not be reported missing") },
        )

        val olderTicket = coordinator.replace(PlaybackReplacementRequest(1L, 1L))
        olderTicket.job.join()
        val newerTicket = requireNotNull(replacementTicket)
        newerTicket.job.join()

        assertEquals(listOf(2L), readyQueueIds)
        assertFalse(coordinator.isCurrent(olderTicket))
        assertTrue(coordinator.isCurrent(newerTicket))
        scope.cancel()
    }

    @Test
    fun liveTranscodeHelpersHandleOtherQueriesAndNegativeTimelineValues() {
        assertFalse(LiveTranscodeStreamUrl.isLiveTranscode("http://server/api/stream/1"))
        assertTrue(
            LiveTranscodeStreamUrl.isLiveTranscode(
                "http://server/api/stream/1?token=local&transcode=true",
            ),
        )
        assertEquals(
            0L,
            LiveTranscodeStreamUrl.startPositionMs("http://server/api/stream/1?transcode=true&token=local"),
        )
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://server/api/stream/1?"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://server/api/stream/1?start"))
        assertEquals(0L, LiveTranscodeStreamUrl.startPositionMs("http://server/api/stream/1?start=0"))
        assertEquals(
            2_500L,
            LiveTranscodeStreamUrl.startPositionMs("http://server/api/stream/1?token=local&start=2.5"),
        )
        assertEquals(0L, LiveTranscodePosition.absolutePositionMs(-10L, -20L))
        assertEquals(0L, LiveTranscodePosition.pipePositionMs(-10L, 20L))
        assertEquals(5L, LiveTranscodePosition.pipePositionMs(25L, 20L))
    }

    private fun sourceFor(result: FileSourceResolution): PlaybackSource = object : PlaybackSource {
        override suspend fun resolveFileSource(songId: Long): FileSourceResolution = result
        override fun streamUrl(fileId: Long): String = "source://$fileId"
    }

    private fun sourceFor(result: PlaybackResolution): PlaybackSource = object : PlaybackSource {
        override suspend fun resolveFileSource(songId: Long): FileSourceResolution =
            error("resolvePlayback should be used")

        override suspend fun resolvePlayback(songId: Long, forceTranscode: Boolean): PlaybackResolution = result

        override fun streamUrl(fileId: Long): String = "source://$fileId"
    }

    private fun desiredState(
        queueId: Long,
        state: String = "playing",
        positionMs: Long = 0L,
        seekSequence: Long = 0L,
    ) = DesiredPlaybackState().also {
        it.update(
            QueueSnapshot(
                playing = NowPlaying(queueId = queueId),
                state = state,
                positionMs = positionMs,
                seekSequence = seekSequence,
            ),
        )
    }
}
