package com.homektv.library;

import com.homektv.common.ApiException;
import com.homektv.config.AppProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Controlled filesystem mutation guard for managed library cleanup paths.
 * Guarantees EXTERNAL_READ_ONLY mode and library root containment before any delete.
 */
public final class ManagedFilesystemGuard {

    private ManagedFilesystemGuard() {
    }

    public static void deleteManagedLibraryFile(AppProperties props, Path candidate) {
        LibraryModePolicy.requireManaged(props, "删除曲库文件");
        Path root = Path.of(props.getKtvLibraryPath()).toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new ApiException("INVALID_LIBRARY_PATH", "拒绝删除 KTV 曲库目录以外的文件：" + path);
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new ApiException("DELETE_LIBRARY_FILE_FAILED", "删除 KTV 文件失败：" + path + "，" + e.getMessage());
        }
    }
}
