package com.homektv.web;

import com.homektv.domain.SongFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the live pipe command while keeping HTTP admission and process ownership elsewhere. */
final class LiveTranscodeCommandBuilder {
    private final String ffmpegPath;

    LiveTranscodeCommandBuilder(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    List<String> build(File source, SongFile songFile, Double startSeconds) {
        return build(source, songFile, startSeconds, null, false, false);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo) {
        return build(source, songFile, startSeconds, audioTrack, copyVideo, false);
    }

    List<String> build(File source, SongFile songFile, Double startSeconds,
                       Integer audioTrack, boolean copyVideo, boolean mergeTracks) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        if (startSeconds != null && startSeconds > 0) {
            command.add("-ss");
            command.add(String.format(Locale.US, "%.3f", startSeconds));
        }
        command.add("-i");
        command.add(source.getAbsolutePath());

        boolean shouldMerge = mergeTracks && audioTrack == null && songFile.getAudioTracks() >= 2;
        if (shouldMerge) {
            command.add("-filter_complex");
            command.add("[0:a:0]aformat=sample_rates=48000:channel_layouts=mono[a0];[0:a:1]aformat=sample_rates=48000:channel_layouts=mono[a1];[a0][a1]amerge=inputs=2[aout]");
            command.add("-map");
            command.add("0:v:0");
            command.add("-map");
            command.add("[aout]");
        } else {
            command.add("-map");
            command.add("0:v:0");
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
            command.add("-preset");
            command.add("veryfast");
            command.add("-tune");
            command.add("zerolatency");
            command.add("-pix_fmt");
            command.add("yuv420p");
        }

        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-ar");
        command.add("48000");
        command.add("-ac");
        command.add("2");

        command.add("-f");
        command.add("mp4");
        command.add("-movflags");
        command.add("frag_keyframe+empty_moov+default_base_moof");
        command.add("pipe:1");
        return List.copyOf(command);
    }
}
