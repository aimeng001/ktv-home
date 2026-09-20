package com.homektv.tv.net

/** Unified server media resolution used by Android playback. */
internal sealed interface PlaybackResolution {
    data class Ready(val source: FileSource, val descriptor: PlaybackDescriptor) : PlaybackResolution
    data class Preparing(val source: FileSource, val descriptor: PlaybackDescriptor) : PlaybackResolution
    data class Failed(val source: FileSource, val descriptor: PlaybackDescriptor, val error: KtvApiError) : PlaybackResolution
    data class Absent(val reason: AbsentReason) : PlaybackResolution
    data class Fatal(val error: KtvApiError) : PlaybackResolution
    data class ConfigurationFailure(val error: KtvApiError) : PlaybackResolution
    data class Retryable(val error: KtvApiError) : PlaybackResolution
}
