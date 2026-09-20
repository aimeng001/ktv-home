package com.homektv.tv.player

import com.homektv.tv.net.FileSource
import com.homektv.tv.net.FileSourceResolution
import com.homektv.tv.net.AudioLayout
import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.NowPlaying
import com.homektv.tv.net.QueueSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {    @Test
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
}


