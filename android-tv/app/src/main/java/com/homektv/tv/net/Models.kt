package com.homektv.tv.net

import kotlinx.serialization.Serializable

/**
 * 服务端 WS/REST 数据模型，对应 server 端 DTO（详设§4.2/§11.1）。
 * 广播事件 queue_updated/now_playing/player_state/volume_changed/vocal_changed
 * 的 payload 均为完整 QueueSnapshot（见 server ControlController.broadcast）。
 */

@Serializable
data class SongDto(
    val id: Long = 0,
    val title: String = "",
    val artist: String = "",
    val mediaType: String = "AUDIO",   // KTV_VIDEO / MV / AUDIO
    val hasVocalTrack: Boolean = false,
    val durationMs: Int = 0,
    val lyricType: String = "none",    // word / line / sub / none
    val coverUrl: String? = null,      // 形如 /api/cover/{id}
    val playCount: Int = 0,
)

@Serializable
data class LibraryStatus(
    val totalSongs: Long = 0,
)

@Serializable
data class ReleaseAnnouncement(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "",
    val message: String = "",
)

@Serializable
data class ApkPackageInfo(
    val abi: String = "",
    val available: Boolean = false,
    val url: String = "",
    val fileName: String = "",
    val size: Long = 0,
)

@Serializable
data class TvPackages(
    val armeabiV7a: ApkPackageInfo = ApkPackageInfo(),
    val arm64V8a: ApkPackageInfo = ApkPackageInfo(),
)

@Serializable
data class ReleaseInfo(
    val version: String = "",
    val versionCode: Long = 0,
    val announcement: ReleaseAnnouncement = ReleaseAnnouncement(),
    val tv: TvPackages = TvPackages(),
)

@Serializable
data class StandbyContent(
    val welcomeText: String = "今晚开唱",
    val subtitle: String = "手机点歌，电视欢唱\n一家人的客厅 KTV",
    val carouselEnabled: Boolean = true,
    val antiBurn: Boolean = true,
    val intervalSeconds: Int = 8,
    val source: String = "mixed",
    val videoScaleMode: String = "zoom", // fit / zoom / fill，默认等比裁切铺满
    val miniQr: Boolean = true,
    val logoUrl: String? = null,
    val songs: List<SongDto> = emptyList(),
)

@Serializable
data class NowPlaying(
    val queueId: Long? = null,
    val song: SongDto? = null,
    val orderedByNick: String? = null,
)

@Serializable
data class QueueEntry(
    val queueId: Long? = null,
    val song: SongDto? = null,
    val orderedBy: Long? = null,
    val orderedByNick: String? = null,
    val status: String = "waiting",
)

@Serializable
data class QueueSnapshot(
    val playing: NowPlaying? = null,
    val list: List<QueueEntry> = emptyList(),
    val state: String = "idle",         // idle / playing / paused
    val volume: Int = 60,
    val muted: Boolean = false,
    val vocalMode: String = "accompaniment", // original / accompaniment
    val audioLayout: AudioLayout = AudioLayout(),
    val tvOnline: Boolean = false,
    val connectedPhones: Long = 0,
    val positionMs: Long = 0,
    val seekSequence: Long = 0,
)

/** Platform-neutral audio semantics; PCM/Media3 implementation stays client-local. */
@Serializable
data class AudioLayout(
    // The old protocol had no layout field but did expose vocalTrackIndex;
    // DUAL_TRACK is the compatibility fallback. New server responses always
    // send an explicit layout, including NORMAL_STEREO.
    val layout: String = "DUAL_TRACK",
    val originalTrackIndex: Int? = null,
    val accompanimentTrackIndex: Int? = null,
    val originalChannel: String = "LEFT",
    val accompanimentChannel: String = "RIGHT",
) {
    companion object {
        fun normalStereo(): AudioLayout = AudioLayout(layout = "NORMAL_STEREO")
    }
}

/**
 * 歌曲详情 GET /api/songs/{id}（详设§11.1）。
 * now_playing 快照只带 song.id，拉流需要 file_id（=song_files.id），
 * 故播放前拉一次详情，取 priority 最高的文件源。
 */
@Serializable
data class SongDetail(
    val id: Long = 0,
    val title: String = "",
    val artist: String = "",
    val mediaType: String = "AUDIO",
    val hasVocalTrack: Boolean = false,
    val durationMs: Int = 0,
    val lyricType: String = "none",
    val coverUrl: String? = null,
    val lyricUrl: String? = null,
    val files: List<FileSource> = emptyList(),
)

@Serializable
data class FileSource(
    val id: Long = 0,                   // = song_files.id = /api/stream/{id} 的 file_id
    val format: String = "",
    val audioTracks: Int = 1,
    val vocalTrackIndex: Int? = null,   // 伴唱轨 index（P1.29 切轨用）
    val resolution: String? = null,
    val priority: Int = 0,
    val audioLayout: AudioLayout = AudioLayout(),
)
