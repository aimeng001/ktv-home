package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.web.ApiException;

import java.nio.file.Path;

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
        Path sourceRoot = Path.of(props.getSourceLibraryPath()).toAbsolutePath().normalize();
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        if (normalizedCacheRoot.startsWith(sourceRoot)) {
            throw new ApiException(EXTERNAL_READ_ONLY_CODE,
                    "EXTERNAL_READ_ONLY：数据缓存目录不能位于外部曲库目录内");
        }
    }
}
