package com.homektv.library;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Allocates an output path with an atomic create instead of a check-then-use race. */
public final class OutputPathReservation {

    private static final int MAX_CANDIDATES = 10_000;

    private OutputPathReservation() { }

    public static Path reserve(Path desired) throws IOException {
        Path normalized = desired.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("输出路径没有父目录：" + desired);
        Files.createDirectories(parent);

        String filename = normalized.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int index = 1; index <= MAX_CANDIDATES; index++) {
            Path candidate = index == 1
                    ? normalized
                    : parent.resolve(base + "-" + index + extension);
            try {
                Files.createFile(candidate);
                return candidate;
            } catch (FileAlreadyExistsException alreadyExists) {
                // A concurrent worker or a pre-existing file owns this name.
            }
        }
        throw new IOException("无法为输出文件分配唯一文件名：" + normalized);
    }
}
