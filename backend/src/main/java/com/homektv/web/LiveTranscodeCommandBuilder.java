package com.homektv.web;

import com.homektv.domain.SongFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the live pipe command while keeping HTTP admission and process ownership elsewhere. */
final class LiveTranscodeCommandBuilder {
    static final int DEFAULT_LIVE_TRANSCODE_THREADS = 2;
    static final int MAX_LIVE_TRANSCODE_THREADS = 4;
    static final String DEFAULT_PROBE_SIZE = "2097152";
    static final String DEFAULT_ANALYZE_DURATION = "1500000";

    private final String ffmpegPath;
    private final int liveTranscodeThreads;

    LiveTranscodeCommandBuilder(String ffmpegPath) {
        this(ffmpegPath, DEFAULT_LIVE_TRANSCODE_THREADS);
    }

    LiveTranscodeCommandBuilder(String ffmpegPath, int liveTranscodeThreads) {
        this.ffmpegPath = ffmpegPath;
        this.liveTranscodeThreads = Math.max(1, Math.min(liveTranscodeThreads, MAX_LIVE_TRANSCODE_THREADS));
    }

    List<String> build(File source, SongFile songFile, Double startSeconds) {
        return build(source, songFile, startSeconds, null, false, false, false);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo) {
        return build(source, songFile, startSeconds, audioTrack, copyVideo, false, false);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo, boolean mergeTracks) {
        return build(source, songFile, startSeconds, audioTrack, copyVideo, false, mergeTracks);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo, boolean copyAudio, boolean mergeTracks) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-probesize");
        command.add(DEFAULT_PROBE_SIZE);
        command.add("-analyzeduration");
        command.add(DEFAULT_ANALYZE_DURATION);
        if (startSeconds != null && startSeconds > 0) {
            if (copyVideo) {
                // -c:v copy 只能在关键帧切入，保留 -noaccurate_seek 防首帧花屏
                command.add("-noaccurate_seek");
            }
            command.add("-ss");
            command.add(String.format(Locale.US, "%.3f", startSeconds));
        }
        command.add("-i");
        command.add(source.getAbsolutePath());

        boolean shouldMerge = mergeTracks && audioTrack == null && songFile.getAudioTracks() >= 2;
        if (shouldMerge) {
            int trackCount = Math.max(2, songFile.getAudioTracks());
            int origIdx = songFile.getOriginalTrackIndex() != null
                    && songFile.getOriginalTrackIndex() >= 0
                    && songFile.getOriginalTrackIndex() < trackCount
                    ? songFile.getOriginalTrackIndex() : 0;
            int accIdx = songFile.getAccompanimentTrackIndex() != null
                    && songFile.getAccompanimentTrackIndex() >= 0
                    && songFile.getAccompanimentTrackIndex() < trackCount
                    ? songFile.getAccompanimentTrackIndex() : (origIdx == 0 ? 1 : 0);
            int leftSourceTrack = (origIdx >= 2 || accIdx >= 2) ? origIdx : 0;
            int rightSourceTrack = (origIdx >= 2 || accIdx >= 2) ? accIdx : 1;
            command.add("-filter_complex");
            command.add("[0:a:" + leftSourceTrack + "]aformat=sample_rates=48000:channel_layouts=mono[a0];[0:a:"
                    + rightSourceTrack + "]aformat=sample_rates=48000:channel_layouts=mono[a1];[a0][a1]amerge=inputs=2[aout]");
            command.add("-map");
            command.add("0:V:0?");
            command.add("-map");
            command.add("[aout]");
        } else {
            command.add("-map");
            command.add("0:V:0?");
            if (audioTrack != null && audioTrack >= 0) {
                command.add("-map");
                command.add("0:a:" + audioTrack);
            } else {
                int audioTracks = songFile.getAudioTracks();
                if (audioTracks > 1) {
                    for (int index = 0; index < audioTracks; index++) {
                        command.add("-map");
                        command.add("0:a:" + index);
                    }
                } else {
                    command.add("-map");
                    command.add("0:a?");
                }
            }
        }

        if (copyVideo) {
            command.add("-c:v");
            command.add("copy");
        } else {
            command.add("-c:v");
            command.add("libx264");
            command.add("-threads");
            command.add(String.valueOf(liveTranscodeThreads));
            command.add("-preset");
            command.add("veryfast");
            command.add("-tune");
            command.add("zerolatency");
            command.add("-pix_fmt");
            command.add("yuv420p");
        }

        boolean effectiveCopyAudio = copyAudio && !shouldMerge;
        if (effectiveCopyAudio) {
            command.add("-c:a");
            command.add("copy");
        } else {
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("192k");
            command.add("-ar");
            command.add("48000");
            command.add("-ac");
            command.add("2");
        }

        command.add("-avoid_negative_ts");
        command.add("make_zero");

        command.add("-f");
        command.add("mp4");
        command.add("-movflags");
        command.add("frag_keyframe+empty_moov+default_base_moof");
        command.add("pipe:1");
        return List.copyOf(command);
    }
}
