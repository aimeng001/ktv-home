package com.homektv.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a small, sanitized support archive from an allowlisted diagnostics object and local logs. */
@Service
public class DiagnosticBundleService {
    public static final int MAX_LOG_BYTES = 1_048_576;
    public static final int MAX_BUNDLE_BYTES = 5_242_880;
    public static final int MAX_ENTRIES = 20;
    private static final int MAX_TOTAL_LOG_BYTES = 4_194_304;

    private final ObjectMapper mapper;
    private final Semaphore generation = new Semaphore(1);

    public DiagnosticBundleService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] create(Path logsRoot, Map<String, Object> diagnostics) throws IOException {
        if (!generation.tryAcquire()) {
            throw new IllegalStateException("diagnostic bundle generation is already running");
        }
        try {
            return createBounded(logsRoot, diagnostics);
        } finally {
            generation.release();
        }
    }

    private byte[] createBounded(Path logsRoot, Map<String, Object> diagnostics) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            byte[] summary = SecretRedactor.redact(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(diagnostics)).getBytes(StandardCharsets.UTF_8);
            add(zip, "diagnostics.json", summary);

            int remaining = MAX_TOTAL_LOG_BYTES;
            int index = 1;
            for (Path log : safeLogs(logsRoot)) {
                if (index >= MAX_ENTRIES || remaining <= 0) break;
                int limit = Math.min(MAX_LOG_BYTES, remaining);
                byte[] raw;
                try (InputStream input = Files.newInputStream(log)) {
                    raw = input.readNBytes(limit);
                }
                byte[] sanitized = SecretRedactor.redact(new String(raw, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
                if (sanitized.length > limit) {
                    sanitized = java.util.Arrays.copyOf(sanitized, limit);
                }
                add(zip, "logs/log-%03d.txt".formatted(index++), sanitized);
                remaining -= sanitized.length;
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_BUNDLE_BYTES) {
            throw new IOException("diagnostic bundle exceeded its hard size limit");
        }
        return result;
    }

    private List<Path> safeLogs(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return List.of();
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try (var files = Files.list(normalizedRoot)) {
            return files
                    .filter(path -> path.toAbsolutePath().normalize().startsWith(normalizedRoot))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .limit(MAX_ENTRIES - 1L)
                    .toList();
        }
    }

    private void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
