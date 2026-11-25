package com.homektv.web;

import com.homektv.domain.SongFile;
import com.homektv.library.TranscodeHardwareService;

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
        return build(source, songFile, startSeconds, null, false, false, false, null);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo) {
        return build(source, songFile, startSeconds, audioTrack, copyVideo, false, false, null);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo, boolean mergeTracks) {
        return build(source, songFile, startSeconds, audioTrack, copyVideo, false, mergeTracks, null);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo, boolean copyAudio, boolean mergeTracks) {
        return build(source, songFile, startSeconds, audioTrack, copyVideo, copyAudio, mergeTracks, null);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo, boolean copyAudio, boolean mergeTracks,
                       TranscodeHardwareService.HardwareStatus hardwareStatus) {
        boolean useHardware = !copyVideo
                && hardwareStatus != null
                && hardwareStatus.available()
                && hardwareStatus.supportedCodecs() != null
                && hardwareStatus.supportedCodecs().contains("h264");
        boolean useVaapi = useHardware && "vaapi".equals(hardwareStatus.acceleration());
        boolean useRkmpp = useHardware && "rkmpp".equals(hardwareStatus.acceleration());

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-probesize");
        command.add(DEFAULT_PROBE_SIZE);
        command.add("-analyzeduration");
        command.add(DEFAULT_ANALYZE_DURATION);
        if (useVaapi && hardwareStatus.device() != null && !hardwareStatus.device().isBlank()) {
            command.add("-vaapi_device");
            command.add(hardwareStatus.device());
        }
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
            String audioFilter = "[0:a:" + leftSourceTrack + "]aresample=async=1:first_pts=0,aformat=sample_rates=48000:channel_layouts=mono[a0];"
                    + "[0:a:" + rightSourceTrack + "]aresample=async=1:first_pts=0,aformat=sample_rates=48000:channel_layouts=mono[a1];"
                    + "[a0][a1]join=inputs=2:channel_layout=stereo:map=0.0-FL|1.0-FR[aout]";
            command.add("-filter_complex");
            if (useVaapi) {
                command.add("[0:V:0]format=nv12,hwupload[vout];" + audioFilter);
                command.add("-map");
                command.add("[vout]");
            } else {
                command.add(audioFilter);
                command.add("-map");
                command.add("0:V:0?");
            }
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
        } else if (useVaapi) {
            if (!shouldMerge) {
                command.add("-vf");
                command.add("format=nv12,hwupload");
            }
            command.add("-c:v");
            command.add("h264_vaapi");
            // Intel Jasper Lake (如 N5105) 在未加载 HuC 固件时 VBR 易失败，CQP 可稳定启用核显硬编。
            command.add("-rc_mode");
            command.add("CQP");
            command.add("-qp");
            command.add("24");
            command.add("-g");
            command.add("30");
            command.add("-bf");
            command.add("0");
        } else if (useRkmpp) {
            command.add("-pix_fmt");
            command.add("nv12");
            command.add("-c:v");
            command.add("h264_rkmpp");
            command.add("-g");
            command.add("30");
            command.add("-bf");
            command.add("0");
            command.add("-b:v");
            command.add("3000k");
            command.add("-maxrate");
            command.add("4500k");
            command.add("-bufsize");
            command.add("9000k");
        } else {
            command.add("-c:v");
            command.add("libx264");
            command.add("-threads");
            command.add(String.valueOf(liveTranscodeThreads));
            command.add("-preset");
            command.add("superfast");
            command.add("-bf");
            command.add("0");
            command.add("-g");
            command.add("30");
            command.add("-keyint_min");
            command.add("30");
            command.add("-sc_threshold");
            command.add("0");
            command.add("-crf");
            command.add("23");
            command.add("-maxrate");
            command.add("4000k");
            command.add("-bufsize");
            command.add("8000k");
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
