package com.homektv.tv.ui

import android.content.Context
import android.content.Intent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.net.AppConfig
import com.homektv.tv.session.DeviceMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real MainActivity WebSocket -> REST resolution -> Media3 startup path. */
@RunWith(AndroidJUnit4::class)
class PlaybackStartupInstrumentedTest {
    @Test
    fun immediateOptionalAssetsProveTheLocalFixtureCanStartPlayback() {
        withActivity(holdLyric = false) { fixture, player, _ ->
            fixture.sendPlayingSnapshot()

            assertTrue("fixture should receive the media request", fixture.mediaRequested.await(15, TimeUnit.SECONDS))
            assertTrue("Media3 should reach READY with the local synthetic asset", player.ready.await(30, TimeUnit.SECONDS))
            assertNull("Media3 should accept the local synthetic asset", player.error.get())
        }
    }

    @Test
    fun pendingLyricResponseDoesNotBlockMediaStartup() {
        withActivity(holdLyric = true) { fixture, player, _ ->
            fixture.sendPlayingSnapshot()

            assertTrue("the activity should request lyrics so the delay is exercising the target path",
                fixture.lyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue(
                "MainActivity must request media while the lyric response is still held",
                fixture.mediaRequested.await(2, TimeUnit.SECONDS),
            )
            assertTrue("Media3 should reach READY before lyrics are released", player.ready.await(30, TimeUnit.SECONDS))
            assertNull("Media3 should accept the local synthetic asset", player.error.get())
        }
    }

    @Test
    fun pauseAndSeekApplyWhileOptionalLyricsAreStillPending() {
        withActivity(holdLyric = true, holdCover = true) { fixture, player, _ ->
            fixture.sendPlayingSnapshot(mediaType = "AUDIO")

            assertTrue("the activity should request lyrics", fixture.lyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue("the activity should request artwork", fixture.coverRequested.await(10, TimeUnit.SECONDS))
            assertTrue("media must start while lyrics are held", fixture.mediaRequested.await(2, TimeUnit.SECONDS))
            assertTrue("Media3 should reach READY", player.ready.await(30, TimeUnit.SECONDS))

            fixture.sendSnapshot(
                state = "paused",
                positionMs = 5_000L,
                seekSequence = 1L,
                revision = 2L,
                mediaType = "AUDIO",
                volume = 22,
            )

            assertTrue("pause should apply without waiting for lyrics", player.paused.await(2, TimeUnit.SECONDS))
            assertTrue("seek should apply without waiting for lyrics", player.seeked.await(2, TimeUnit.SECONDS))
            assertTrue("volume should apply while both optional assets are pending",
                awaitSystemVolumePercent(22))
            assertNull("Media3 should accept the local synthetic asset", player.error.get())
        }
    }

    @Test
    fun pendingAudioCoverDoesNotBlockMediaStartup() {
        withActivity(holdLyric = false, holdCover = true) { fixture, player, _ ->
            fixture.sendPlayingSnapshot(mediaType = "AUDIO")

            assertTrue("lyrics should return without blocking", fixture.lyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue("cover should be requested and remain held", fixture.coverRequested.await(10, TimeUnit.SECONDS))
            assertTrue("MainActivity must request media while cover is held",
                fixture.mediaRequested.await(2, TimeUnit.SECONDS))
            assertTrue("Media3 should reach READY before the cover is released", player.ready.await(30, TimeUnit.SECONDS))
            assertNull("Media3 should accept the local synthetic asset", player.error.get())
        }
    }

    @Test
    fun previousPlaybackLyricsDoNotReplaceTheCurrentSongAfterAQueueChange() {
        withActivity(holdLyric = true) { fixture, player, scenario ->
            fixture.sendPlayingSnapshot()
            assertTrue("first song lyrics should be held", fixture.lyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue("first song media should start", fixture.mediaRequested.await(10, TimeUnit.SECONDS))
            assertTrue("Media3 should reach READY for the first song", player.ready.await(30, TimeUnit.SECONDS))

            fixture.sendSnapshot(
                state = "playing",
                positionMs = 0L,
                seekSequence = 0L,
                revision = 2L,
                queueId = 502L,
                songId = 92001L,
            )

            assertTrue("the new song should request its own lyrics", fixture.secondLyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue("the new song should request its own media", fixture.secondMediaRequested.await(10, TimeUnit.SECONDS))
            assertTrue("new song lyrics should reach the current UI", awaitLyricDescription(scenario, "新歌歌词"))

            fixture.releaseLyricResponse()
            Thread.sleep(250L)
            assertTrue("late lyrics from the previous song must not replace the current line",
                awaitLyricDescription(scenario, "新歌歌词"))
        }
    }

    private fun awaitLyricDescription(scenario: ActivityScenario<MainActivity>, expected: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val description = AtomicReference<String?>(null)
            scenario.onActivity { activity ->
                description.set(activity.findViewById<android.view.View>(R.id.txtLyricPrevious).contentDescription?.toString())
            }
            if (description.get() == expected) return true
            Thread.sleep(50L)
        }
        return false
    }

    private fun awaitSystemVolumePercent(percent: Int): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val target = percent.coerceIn(0, 100) * audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) / 100
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == target) return true
            Thread.sleep(50L)
        }
        return false
    }

    @Test
    fun optionalLyricAndCoverFailuresDoNotStopAudioPlayback() {
        withActivity(holdLyric = false, failOptionalAssets = true) { fixture, player, _ ->
            fixture.sendPlayingSnapshot(mediaType = "AUDIO")

            assertTrue("audio playback should request lyrics", fixture.lyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue("audio playback should request artwork", fixture.coverRequested.await(10, TimeUnit.SECONDS))
            assertTrue("optional HTTP failures must not block media", fixture.mediaRequested.await(2, TimeUnit.SECONDS))
            assertTrue("Media3 should reach READY despite optional HTTP failures", player.ready.await(30, TimeUnit.SECONDS))
            assertNull("Media3 should accept the local synthetic asset", player.error.get())
        }
    }

    @Test
    fun invalidOptionalAssetContentDoesNotStopAudioPlayback() {
        withActivity(holdLyric = false, corruptOptionalAssets = true) { fixture, player, _ ->
            fixture.sendPlayingSnapshot(mediaType = "AUDIO")

            assertTrue("audio playback should request lyrics", fixture.lyricRequested.await(10, TimeUnit.SECONDS))
            assertTrue("audio playback should request artwork", fixture.coverRequested.await(10, TimeUnit.SECONDS))
            assertTrue("invalid optional content must not block media", fixture.mediaRequested.await(2, TimeUnit.SECONDS))
            assertTrue("Media3 should reach READY despite invalid optional content", player.ready.await(30, TimeUnit.SECONDS))
            assertNull("Media3 should accept the local synthetic asset", player.error.get())
        }
    }

    private fun withActivity(
        holdLyric: Boolean,
        holdCover: Boolean = false,
        failOptionalAssets: Boolean = false,
        corruptOptionalAssets: Boolean = false,
        block: (StartupServer, PlayerObservation, ActivityScenario<MainActivity>) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("ktv_tv", Context.MODE_PRIVATE)
        val previousPreferences = preferences.all.toMap()
        val mediaBytes = instrumentation.context.assets.open("ktv_synthetic_dual_track.mp4").use { it.readBytes() }
        val fixture = StartupServer(mediaBytes, holdLyric, holdCover, failOptionalAssets, corruptOptionalAssets)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            AppConfig(context).apply {
                serverHost = fixture.hostPort
                saveMode(DeviceMode.PLAYER, fixture.hostPort)
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            assertTrue("MainActivity should connect to the local WebSocket fixture",
                fixture.webSocketReady.await(15, TimeUnit.SECONDS))
            val observation = PlayerObservation()
            scenario.onActivity { activity ->
                val view = activity.findViewById<PlayerView>(R.id.playerView)
                observation.attach(requireNotNull(view.player))
            }
            block(fixture, observation, requireNotNull(scenario))
        } finally {
            fixture.releaseLyricResponse()
            scenario?.close()
            fixture.close()
            restorePreferences(preferences, previousPreferences)
        }
    }

    private fun restorePreferences(
        preferences: android.content.SharedPreferences,
        values: Map<String, Any?>,
    ) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "test AppConfig preferences should be restored" }
    }

    private class PlayerObservation {
        val ready = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val seeked = CountDownLatch(1)
        val error = AtomicReference<String?>(null)

        fun attach(player: Player) {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    this@PlayerObservation.error.set(error.toString())
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) paused.countDown()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs >= 5_000L) {
                        seeked.countDown()
                    }
                }
            })
        }
    }

    private class StartupServer(
        private val mediaBytes: ByteArray,
        private val holdLyric: Boolean,
        private val holdCover: Boolean,
        private val failOptionalAssets: Boolean,
        private val corruptOptionalAssets: Boolean,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val workers: ExecutorService = Executors.newCachedThreadPool()
        private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        private val closed = AtomicBoolean(false)
        private val peer = AtomicReference<WebSocketPeer?>(null)
        val webSocketReady = CountDownLatch(1)
        val lyricRequested = CountDownLatch(1)
        val secondLyricRequested = CountDownLatch(1)
        val coverRequested = CountDownLatch(1)
        val mediaRequested = CountDownLatch(1)
        val secondMediaRequested = CountDownLatch(1)
        private val releaseLyric = CountDownLatch(1)
        private val releaseCover = CountDownLatch(1)

        val hostPort: String get() = "127.0.0.1:${server.localPort}"

        init {
            Thread({ acceptLoop() }, "ktv-startup-fixture-accept").apply {
                isDaemon = true
                start()
            }
        }

        fun sendPlayingSnapshot(mediaType: String = "MV") {
            sendSnapshot("playing", 0L, 0L, 1L, mediaType)
        }

        fun sendSnapshot(
            state: String,
            positionMs: Long,
            seekSequence: Long,
            revision: Long,
            mediaType: String = "MV",
            queueId: Long = 501L,
            songId: Long = 91001L,
            volume: Int = 60,
            muted: Boolean = false,
        ) {
            val title = if (songId == 92001L) "新歌" else "开播测试"
            val snapshot = """{"playing":{"queueId":$queueId,"song":{"id":$songId,"title":"$title","artist":"本地夹具","mediaType":"$mediaType","durationMs":30000}},"state":"$state","volume":$volume,"muted":$muted,"vocalMode":"original","positionMs":$positionMs,"seekSequence":$seekSequence,"stateRevision":$revision}"""
            requireNotNull(peer.get()) { "WebSocket fixture is not connected" }.sendText(
                """{"type":"sync_full","payload":$snapshot}""",
            )
        }

        fun releaseLyricResponse() {
            releaseLyric.countDown()
        }

        fun releaseCoverResponse() {
            releaseCover.countDown()
        }

        private fun acceptLoop() {
            while (!closed.get()) {
                try {
                    val socket = server.accept()
                    sockets += socket
                    workers.execute { serve(socket) }
                } catch (_: Exception) {
                    if (!closed.get()) Thread.sleep(20L)
                }
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.soTimeout = 1_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = readHeaders(input)
                if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                    serveWebSocket(socket, input, headers)
                } else {
                    serveHttp(socket, requestLine, headers)
                }
            } catch (_: Exception) {
                // Activity teardown and Media3 range cancellation close local requests normally.
            } finally {
                sockets.remove(socket)
                runCatching { socket.close() }
            }
        }

        private fun serveWebSocket(socket: Socket, input: BufferedInputStream, headers: Map<String, String>) {
            val key = requireNotNull(headers["sec-websocket-key"])
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.ISO_8859_1)),
            )
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                .toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            val connected = WebSocketPeer(input, output)
            peer.set(connected)
            connected.sendText("""{"type":"player_role","payload":{"role":"ACTIVE","generation":1,"lease_ms":300000}}""")
            webSocketReady.countDown()
            connected.readUntilClosed()
        }

        private fun serveHttp(socket: Socket, requestLine: String, headers: Map<String, String>) {
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            when {
                path.matches(Regex("/api/songs/(91001|92001)")) -> {
                    val songId = path.substringAfterLast('/').toLong()
                    val title = if (songId == 92001L) "新歌" else "开播测试"
                    val fileId = songId + 1
                    respondJson(socket, """{"id":$songId,"title":"$title","artist":"本地夹具","mediaType":"MV","durationMs":30000,"files":[{"id":$fileId,"format":"mp4","audioTracks":2,"vocalTrackIndex":1,"priority":1,"ready":true,"audioLayout":{"layout":"DUAL_TRACK","originalTrackIndex":0,"accompanimentTrackIndex":1}}]}""")
                }
                path.matches(Regex("/api/playback/resolve/(91002|92002)")) -> {
                    val fileId = path.substringAfterLast('/').toLong()
                    respondJson(socket, """{"kind":"NATIVE","status":"READY","sourceFileId":$fileId,"streamUrl":"/api/stream/$fileId","audioTracks":2,"vocalTrackIndex":1,"audioLayout":{"layout":"DUAL_TRACK","originalTrackIndex":0,"accompanimentTrackIndex":1}}""")
                }
                path.matches(Regex("/api/lyric/(91001|92001)")) -> {
                    val songId = path.substringAfterLast('/').toLong()
                    if (songId == 91001L) {
                        lyricRequested.countDown()
                        if (holdLyric) releaseLyric.await(20, TimeUnit.SECONDS)
                    } else {
                        secondLyricRequested.countDown()
                    }
                    val lyricText = when {
                        corruptOptionalAssets -> "not an lrc document"
                        songId == 92001L -> "[00:00.00]新歌歌词"
                        else -> "[00:00.00]旧歌歌词"
                    }
                    val status = if (failOptionalAssets) 500 else 200
                    respond(socket, status, "text/plain; charset=utf-8", "$lyricText\n".toByteArray(StandardCharsets.UTF_8))
                }
                path.matches(Regex("/api/cover/(91001|92001)")) -> {
                    coverRequested.countDown()
                    if (holdCover) releaseCover.await(20, TimeUnit.SECONDS)
                    when {
                        failOptionalAssets -> respond(socket, 500, "application/octet-stream", ByteArray(0))
                        corruptOptionalAssets -> respond(socket, 200, "application/octet-stream", byteArrayOf(0, 1, 2, 3))
                        else -> respond(socket, 404, "application/octet-stream", ByteArray(0))
                    }
                }
                path.matches(Regex("/api/stream/(91002|92002)")) -> {
                    if (path.endsWith("92002")) secondMediaRequested.countDown() else mediaRequested.countDown()
                    respondMedia(socket, headers["range"])
                }
                else -> respondJson(socket, "{}", status = 404)
            }
        }

        private fun respondMedia(socket: Socket, rangeHeader: String?) {
            val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            val start = range?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, mediaBytes.lastIndex) ?: 0
            val requestedEnd = range?.groupValues?.get(2)?.toIntOrNull() ?: mediaBytes.lastIndex
            val end = requestedEnd.coerceIn(start, mediaBytes.lastIndex)
            val length = end - start + 1
            val output = BufferedOutputStream(socket.getOutputStream())
            val status = if (range == null) "200 OK" else "206 Partial Content"
            output.write("HTTP/1.1 $status\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            if (range != null) {
                output.write("Content-Range: bytes $start-$end/${mediaBytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
            output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(mediaBytes, start, length)
            output.flush()
        }

        private fun respondJson(socket: Socket, body: String, status: Int = 200) =
            respond(socket, status, "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

        private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
            val reason = when (status) {
                200 -> "OK"
                500 -> "Internal Server Error"
                else -> "Not Found"
            }
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Content-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }

        private fun readHeaders(input: BufferedInputStream): Map<String, String> = buildMap {
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) put(line.substring(0, separator).trim().lowercase(), line.substring(separator + 1).trim())
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 16 * 1024) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString("ISO-8859-1")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseLyricResponse()
            releaseCoverResponse()
            runCatching { server.close() }
            sockets.forEach { socket -> runCatching { socket.close() } }
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }

        private class WebSocketPeer(
            private val input: BufferedInputStream,
            private val output: BufferedOutputStream,
        ) {
            private val writeLock = Any()

            fun sendText(text: String) = sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))

            private fun sendFrame(opcode: Int, payload: ByteArray) {
                synchronized(writeLock) {
                    output.write(0x80 or opcode)
                    when {
                        payload.size < 126 -> output.write(payload.size)
                        payload.size <= 0xffff -> {
                            output.write(126)
                            output.write(payload.size ushr 8)
                            output.write(payload.size)
                        }
                        else -> error("test WebSocket message is unexpectedly large")
                    }
                    output.write(payload)
                    output.flush()
                }
            }

            fun readUntilClosed() {
                while (true) {
                    val first = try { input.read() } catch (_: java.net.SocketTimeoutException) { continue }
                    if (first < 0) return
                    val second = input.read()
                    if (second < 0) return
                    val opcode = first and 0x0f
                    var length = second and 0x7f
                    if (length == 126) length = (input.read() shl 8) or input.read()
                    if (length > 64 * 1024) return
                    val mask = if (second and 0x80 != 0) readExact(input, 4) else null
                    val payload = readExact(input, length)
                    if (mask != null) payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
                    if (opcode == 8) return
                    if (opcode == 1 && String(payload, StandardCharsets.UTF_8).contains("\"type\":\"ping\"")) {
                        sendText("""{"type":"pong","payload":{"role":"ACTIVE","generation":1,"lease_ms":300000}}""")
                    }
                }
            }

            private fun readExact(input: BufferedInputStream, size: Int): ByteArray {
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val count = input.read(bytes, offset, size - offset)
                    if (count < 0) throw java.io.EOFException("WebSocket closed mid-frame")
                    offset += count
                }
                return bytes
            }
        }
    }
}
