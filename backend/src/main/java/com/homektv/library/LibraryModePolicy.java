package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Centralized policy for the two library ownership modes. */
public final class LibraryModePolicy {

    public static final String EXTERNAL_READ_ONLY_CODE = "EXTERNAL_READ_ONLY";
    public static final String EXTERNAL_FILE_ROLE = "EXTERNAL_READ_ONLY";

    private LibraryModePolicy() {}

    public static boolean isExternalReadOnly(AppProperties props) {
        return props != null && props.isExternalReadOnly();
    }

    public static Path activeLibraryRoot(AppProperties props) {
        return Path.of(isExternalReadOnly(props)
                ? props.getSourceLibraryPath()
                : props.getKtvLibraryPath());
    }

    public static void requireManaged(AppProperties props, String operation) {
        if (isExternalReadOnly(props)) {
            throw new ApiException(EXTERNAL_READ_ONLY_CODE,
                    "EXTERNAL_READ_ONLY：禁止" + operation + "外部曲库源文件");
        }
    }

    public static void requireCacheOutsideExternalSource(AppProperties props, Path cacheRoot) {
        if (!isExternalReadOnly(props)) return;
        Path sourceRoot = resolveBoundaryPath(Path.of(props.getSourceLibraryPath()));
        Path normalizedCacheRoot = resolveBoundaryPath(cacheRoot);
        if (normalizedCacheRoot.startsWith(sourceRoot)) {
            throw new ApiException(EXTERNAL_READ_ONLY_CODE,
                    "EXTERNAL_READ_ONLY：数据缓存目录不能位于外部曲库目录内");
        }
    }

    /**
     * Verifies that an external-library path resolves below the configured source root.
     * This check follows existing symlinks/junctions and therefore cannot be bypassed by
     * storing a lexical path that escapes through a link.
     */
    public static void requireExternalPathInsideSource(AppProperties props, Path candidate) {
        if (!isExternalReadOnly(props)) return;
        Path sourceRoot = resolveBoundaryPath(Path.of(props.getSourceLibraryPath()));
        Path resolvedCandidate = resolveBoundaryPath(candidate);
        if (!resolvedCandidate.startsWith(sourceRoot)) {
            throw new ApiException(EXTERNAL_READ_ONLY_CODE,
                    "EXTERNAL_READ_ONLY：媒体路径必须位于外部曲库目录内");
        }
    }

    /**
     * Resolves the existing portion of a path to its real target and appends the
     * non-existing suffix. This supports checks for files that are about to be created.
     */
    private static Path resolveBoundaryPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        Deque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null) break;
            missing.push(name);
            existing = existing.getParent();
            if (existing == null) break;
        }
        try {
            Path resolved = (existing == null ? absolute.getRoot() : existing).toRealPath();
            while (!missing.isEmpty()) resolved = resolved.resolve(missing.pop());
            return resolved.normalize();
        } catch (IOException e) {
            throw new ApiException(EXTERNAL_READ_ONLY_CODE,
                    "EXTERNAL_READ_ONLY：无法解析文件路径：" + absolute);
        }
    }
}
