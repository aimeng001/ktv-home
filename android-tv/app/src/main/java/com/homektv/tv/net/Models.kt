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
    val artistGender: String = "",
    val mediaType: String = "AUDIO",   // KTV_VIDEO / MV / AUDIO
    val hasVocalTrack: Boolean = false,
    val durationMs: Int = 0,
    val lyricType: String = "none",    // word / line / sub / none
    val coverUrl: String? = null,      // 形如 /api/cover/{id}
    val playCount: Int = 0,
    val artistAvatarUrl: String? = null,
    /** Optional eight-digit commercial catalog number parsed from the filename. */
    val catalogNumber: String = "",
    /** Server-side readiness; old servers omit it and remain backward compatible. */
    val playable: Boolean = true,
    val unavailableReason: String? = null,
)

@Serializable
data class LibraryStatus(
    val totalSongs: Long = 0,
    val libraryMode: String = "UNKNOWN",
    val rootState: String = "UNKNOWN",
    val rootIdentityState: String = "UNKNOWN",
    val countsKnown: Boolean = true,
    val scanState: String = "IDLE",
    val phase: String = "IDLE",
    val discoveredFiles: Long = 0,
    val indexedFiles: Long = 0,
    val indexedSongs: Long = 0,
    val readySongs: Long = 0,
    val probePendingFiles: Long = 0,
    val catalogRevision: Long = 0,
    val statusRevision: Long = 0,
    val errorCode: String? = null,
    val updatedAt: String? = null,
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
    /** Monotonic server snapshot revision; zero is the legacy/unversioned value. */
    val stateRevision: Long = 0,
)

@Serializable
data class QueueSnapshotHeader(
    val playing: NowPlaying? = null,
    val state: String = "idle",
    val volume: Int = 60,
    val muted: Boolean = false,
    val vocalMode: String = "accompaniment",
    val audioLayout: AudioLayout = AudioLayout.normalStereo(),
    val tvOnline: Boolean = false,
    val connectedPhones: Long = 0,
    val positionMs: Long = 0,
    val seekSequence: Long = 0,
    val stateRevision: Long = 0,
) {
    fun toSnapshot(entries: List<QueueEntry>): QueueSnapshot = QueueSnapshot(
        playing = playing,
        list = entries,
        state = state,
        volume = volume,
        muted = muted,
        vocalMode = vocalMode,
        audioLayout = audioLayout,
        tvOnline = tvOnline,
        connectedPhones = connectedPhones,
        positionMs = positionMs,
        seekSequence = seekSequence,
        stateRevision = stateRevision,
    )
}

@Serializable
data class QueueSnapshotChunk(
    val eventType: String = "",
    val syncId: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val last: Boolean = false,
    val header: QueueSnapshotHeader? = null,
    val entries: List<QueueEntry> = emptyList(),
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
    val language: String = "",
    val tags: List<String> = emptyList(),
    val mediaType: String = "AUDIO",
    val hasVocalTrack: Boolean = false,
    val durationMs: Int = 0,
    val lyricType: String = "none",
    val coverUrl: String? = null,
    val lyricUrl: String? = null,
    val playCount: Int = 0,
    val files: List<FileSource> = emptyList(),
    val artistAvatarUrl: String? = null,
    /** Optional eight-digit commercial catalog number parsed from the filename. */
    val catalogNumber: String = "",
)

@Serializable
data class FileSource(
    val id: Long = 0,                   // = song_files.id = /api/stream/{id} 的 file_id
    val format: String = "",
    val audioTracks: Int = 1,
    val vocalTrackIndex: Int? = null,   // 伴唱轨 index（P1.29 切轨用）
    val vocalConfidence: String? = null,
    val resolution: String? = null,
    val priority: Int = 0,
    /** 服务端派生：该文件是否已完成媒体探测；null 表示旧服务端未下发该字段。 */
    val ready: Boolean? = null,
    val audioLayout: AudioLayout = AudioLayout(),
)

@Serializable
data class NamedCount(
    val name: String = "",
    val songCount: Long = 0,
)

@Serializable
data class ArtistItem(
    val artistKey: String = "",
    val name: String = "",
    val initial: String = "#",
    val gender: String = "未知",
    val songCount: Int = 0,
    val artistKind: String = "PERSON",
    val avatarUrl: String? = null,
)

@Serializable
data class ArtistPage(
    val items: List<ArtistItem> = emptyList(),
    val total: Long = 0,
    val page: Int = 0,
    val size: Int = 30,
)

@Serializable
data class SongPage(
    val items: List<SongDto> = emptyList(),
    val total: Long = 0,
    val page: Int = 0,
    val size: Int = 50,
)

@Serializable
data class PlaylistSummary(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val theme: String = "",
    val coverUrl: String? = null,
    val aiGenerated: Boolean = false,
    val songCount: Int = 0,
    val preview: List<SongDto> = emptyList(),
)

@Serializable
data class PlaylistDetail(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val theme: String = "",
    val coverUrl: String? = null,
    val aiGenerated: Boolean = false,
    val songs: List<SongDto> = emptyList(),
)

@Serializable
data class RecentHistoryItem(
    val historyId: Long = 0,
    val song: SongDto? = null,
    val playedBy: Long? = null,
    val playedByNick: String = "家人",
    val mine: Boolean = false,
    val playedAt: String = "",
)

@Serializable
data class UserProfile(
    val id: Long = 0,
    val nickname: String = "",
)

@Serializable
data class RoomHostStatus(
    val claimed: Boolean = false,
    val hostUserId: Long? = null,
    val hostNickname: String? = null,
    val revision: Long = 0,
    val isHost: Boolean = false,
)
