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

        command.add("-map");
        command.add("0:v:0");
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

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-tune");
        command.add("zerolatency");
        command.add("-pix_fmt");
        command.add("yuv420p");

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
