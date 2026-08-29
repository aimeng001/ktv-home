package com.homektv.library;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.ManagedDeleteOperation;
import com.homektv.domain.SongFile;
import com.homektv.repo.ManagedDeleteOperationRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Journaled, recoverable deletion of files owned by the Managed library.
 * External read-only mode is rejected before any repository or filesystem
 * operation can run.
 */
@Service
public class ManagedLibraryDeleteService {

    private static final Logger log = LoggerFactory.getLogger(ManagedLibraryDeleteService.class);
    private static final List<String> RECOVERABLE_STATUSES = List.of(
            ManagedDeleteOperation.PREPARED,
            ManagedDeleteOperation.STAGED,
            ManagedDeleteOperation.COMMITTED,
            ManagedDeleteOperation.RECOVERY_REQUIRED);

    private final AppProperties props;
    private final ManagedDeleteOperationRepository operationRepository;
    private final SongRepository songRepository;
    private final ObjectMapper mapper;

    @Autowired
    public ManagedLibraryDeleteService(AppProperties props,
                                       ManagedDeleteOperationRepository operationRepository,
                                       SongRepository songRepository,
                                       ObjectMapper mapper) {
        this.props = props;
        this.operationRepository = operationRepository;
        this.songRepository = songRepository;
        this.mapper = mapper;
    }

    /** Persist the intent before moving any bytes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String prepare(Long songId, Collection<SongFile> files) {
        LibraryModePolicy.requireManaged(props, "准备删除曲库文件");
        List<SongFile> safeFiles = files == null ? List.of() : List.copyOf(files);
        String operationId = UUID.randomUUID().toString();
        Path libraryRoot = managedRoot();
        Path stagingRoot = stagingRoot(libraryRoot);
        List<StagedFile> manifest = new ArrayList<>();
        for (int index = 0; index < safeFiles.size(); index++) {
            Path source = validateSource(libraryRoot, safeFiles.get(index).getFilePath());
            Path trash = stagingRoot.resolve(operationId)
                    .resolve(index + "-" + source.getFileName())
                    .normalize();
            if (!trash.startsWith(stagingRoot)) {
                throw invalidPath("删除暂存路径越界：" + trash);
            }
            manifest.add(new StagedFile(source.toString(), trash.toString()));
        }
        ManagedDeleteOperation operation = new ManagedDeleteOperation();
        operation.setOperationId(operationId);
        operation.setSongId(songId);
        operation.setStatus(ManagedDeleteOperation.PREPARED);
        operation.setManifest(writeManifest(manifest));
        operationRepository.saveAndFlush(operation);
        return operationId;
    }

    /** Move files without replacing anything already present in the journaled trash. */
    public synchronized void stage(String operationId) {
        LibraryModePolicy.requireManaged(props, "暂存删除曲库文件");
        ManagedDeleteOperation operation = require(operationId);
        if (ManagedDeleteOperation.STAGED.equals(operation.getStatus())) return;
        if (!ManagedDeleteOperation.PREPARED.equals(operation.getStatus())) {
            throw new ApiException("DELETE_OPERATION_INVALID", "删除操作状态不可暂存：" + operation.getStatus());
        }
        List<StagedFile> files = readManifest(operation.getManifest());
        List<StagedFile> moved = new ArrayList<>();
        try {
            Path libraryRoot = managedRoot();
            Path stagingRoot = stagingRoot(libraryRoot);
            for (StagedFile file : files) {
                Path source = validateSource(libraryRoot, file.sourcePath());
                Path trash = validateTrash(stagingRoot, file.trashPath());
                Files.createDirectories(trash.getParent());
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw invalidPath("只允许暂存普通文件：" + source);
                    }
                    moveWithoutReplacement(source, trash);
                    moved.add(file);
                } else if (!Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) {
                    // A missing Managed file is already absent; do not invent a replacement.
                    log.info("删除操作发现文件已不存在：{}", source);
                } else {
                    moved.add(file);
                }
            }
            operation.setStatus(ManagedDeleteOperation.STAGED);
            operationRepository.saveAndFlush(operation);
        } catch (Exception failure) {
            // Only restore entries that this attempt actually staged. A
            // pre-existing source must not be treated as a failed restore (and
            // a pre-existing trash collision must never be overwritten).
            boolean restored = restoreFiles(moved);
            operation.setStatus(restored ? ManagedDeleteOperation.ROLLED_BACK
                    : ManagedDeleteOperation.RECOVERY_REQUIRED);
            operationRepository.saveAndFlush(operation);
            throw new ApiException("DELETE_LIBRARY_FILE_FAILED",
                    "暂存曲库文件失败，已" + (restored ? "回滚" : "保留恢复记录") + "：" + failure.getMessage());
        }
    }

    /** Attach filesystem completion to the surrounding DB transaction. */
    public void registerCompletion(String operationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("删除操作必须在数据库事务中注册完成回调");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    complete(operationId);
                } else {
                    rollback(operationId);
                }
            }
        });
    }

    /** Restore a staged operation; used by rollback callbacks and focused tests. */
    public synchronized void rollback(String operationId) {
        ManagedDeleteOperation operation = operationRepository.findById(operationId).orElse(null);
        if (operation == null || ManagedDeleteOperation.ROLLED_BACK.equals(operation.getStatus())
                || ManagedDeleteOperation.PURGED.equals(operation.getStatus())) return;
        List<StagedFile> files;
        try {
            files = validatedManifest(operation);
        } catch (RuntimeException failure) {
            operation.setStatus(ManagedDeleteOperation.RECOVERY_REQUIRED);
            operationRepository.saveAndFlush(operation);
            log.error("Managed 删除回滚清单校验失败 operationId={}", operationId, failure);
            return;
        }
        boolean restored = restoreFiles(files);
        operation.setStatus(restored ? ManagedDeleteOperation.ROLLED_BACK
                : ManagedDeleteOperation.RECOVERY_REQUIRED);
        operationRepository.saveAndFlush(operation);
    }

    /** Mark the DB deletion durable, then purge only the already committed trash. */
    public synchronized void complete(String operationId) {
        ManagedDeleteOperation operation = operationRepository.findById(operationId).orElse(null);
        if (operation == null || ManagedDeleteOperation.PURGED.equals(operation.getStatus())) return;
        List<StagedFile> files;
        try {
            files = validatedManifest(operation);
        } catch (RuntimeException failure) {
            operation.setStatus(ManagedDeleteOperation.RECOVERY_REQUIRED);
            operationRepository.saveAndFlush(operation);
            log.error("Managed 删除清单校验失败，停止清理 operationId={}", operationId, failure);
            return;
        }
        operation.setStatus(ManagedDeleteOperation.COMMITTED);
        operationRepository.saveAndFlush(operation);
        try {
            for (StagedFile file : files) {
                Files.deleteIfExists(Path.of(file.trashPath()));
            }
            operation.setStatus(ManagedDeleteOperation.PURGED);
            operationRepository.saveAndFlush(operation);
        } catch (IOException failure) {
            log.warn("Managed 删除暂存清理失败，启动时将重试 operationId={}: {}",
                    operationId, failure.getMessage());
        }
    }

    /** Recover operations left by a process crash or an uncompleted transaction callback. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingOperations() {
        for (ManagedDeleteOperation operation : operationRepository
                .findByStatusInOrderByCreatedAtAsc(RECOVERABLE_STATUSES)) {
            try {
                if (songRepository.existsById(operation.getSongId())) {
                    rollback(operation.getOperationId());
                } else {
                    complete(operation.getOperationId());
                }
            } catch (RuntimeException failure) {
                log.error("Managed 删除操作恢复失败 operationId={}", operation.getOperationId(), failure);
            }
        }
    }

    private ManagedDeleteOperation require(String operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new ApiException("DELETE_OPERATION_NOT_FOUND", "删除操作不存在：" + operationId));
    }

    private Path managedRoot() {
        Path root = Path.of(props.getKtvLibraryPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException("INVALID_LIBRARY_PATH", "Managed 曲库目录不存在：" + root);
        }
        try {
            return root.toRealPath();
        } catch (IOException failure) {
            throw new ApiException("INVALID_LIBRARY_PATH", "无法解析 Managed 曲库目录：" + root);
        }
    }

    private Path stagingRoot(Path libraryRoot) {
        Path configuredData = Path.of(props.getDataPath()).toAbsolutePath().normalize();
        if (configuredData.startsWith(libraryRoot)) {
            throw invalidPath("删除暂存目录不能位于 Managed 曲库目录内：" + configuredData);
        }
        try {
            Files.createDirectories(configuredData);
            Path resolved = configuredData.toRealPath();
            if (resolved.startsWith(libraryRoot)) {
                throw invalidPath("删除暂存目录不能解析到 Managed 曲库目录内：" + resolved);
            }
            return resolved.resolve(".ktv-trash").normalize();
        } catch (IOException failure) {
            throw new ApiException("DELETE_LIBRARY_FILE_FAILED", "无法准备删除暂存目录：" + configuredData);
        }
    }

    private Path validateSource(Path libraryRoot, String value) {
        if (value == null || value.isBlank()) throw invalidPath("曲库文件路径为空");
        Path source = Path.of(value).toAbsolutePath().normalize();
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(source)) throw invalidPath("拒绝删除符号链接：" + source);
            try {
                Path realSource = source.toRealPath();
                if (!realSource.startsWith(libraryRoot)) {
                    throw invalidPath("拒绝删除解析后越界的文件：" + source);
                }
                // Persist the canonical path so Windows 8.3 aliases cannot make
                // the later stage operation disagree with the root boundary.
                source = realSource;
            } catch (IOException failure) {
                throw invalidPath("无法解析曲库文件：" + source);
            }
        }
        if (!source.startsWith(libraryRoot)) throw invalidPath("拒绝删除曲库目录以外的文件：" + source);
        return source;
    }

    private Path validateTrash(Path stagingRoot, String value) {
        Path trash = Path.of(value).toAbsolutePath().normalize();
        if (!trash.startsWith(stagingRoot)) throw invalidPath("删除暂存文件路径越界：" + trash);
        if (Files.isSymbolicLink(trash)) throw invalidPath("拒绝处理符号链接暂存文件：" + trash);
        try {
            Path parent = trash.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                if (!parent.toRealPath().startsWith(stagingRoot.toAbsolutePath().normalize())) {
                    throw invalidPath("删除暂存目录解析后越界：" + parent);
                }
            }
        } catch (IOException failure) {
            throw new ApiException("DELETE_LIBRARY_FILE_FAILED", "无法解析删除暂存路径：" + trash);
        }
        return trash;
    }

    private List<StagedFile> validatedManifest(ManagedDeleteOperation operation) {
        List<StagedFile> files = readManifest(operation.getManifest());
        Path libraryRoot = managedRoot();
        Path stagingRoot = stagingRoot(libraryRoot);
        Path operationRoot = stagingRoot.resolve(operation.getOperationId()).normalize();
        for (StagedFile file : files) {
            validateSource(libraryRoot, file.sourcePath());
            Path trash = validateTrash(stagingRoot, file.trashPath());
            if (!trash.startsWith(operationRoot)) {
                throw invalidPath("删除暂存文件不属于当前操作目录：" + trash);
            }
        }
        return files;
    }

    private static void moveWithoutReplacement(Path source, Path trash) throws IOException {
        if (Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("删除暂存目标已存在，拒绝覆盖：" + trash);
        }
        try {
            Files.move(source, trash, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, trash);
        }
    }

    private boolean restoreFiles(List<StagedFile> files) {
        boolean success = true;
        for (StagedFile file : files) {
            try {
                Path source = Path.of(file.sourcePath()).toAbsolutePath().normalize();
                Path trash = Path.of(file.trashPath()).toAbsolutePath().normalize();
                if (!Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    success = false;
                    log.error("恢复时源文件已有同名文件，保留暂存文件：{}", source);
                    continue;
                }
                Files.createDirectories(source.getParent());
                moveWithoutReplacement(trash, source);
            } catch (IOException failure) {
                success = false;
                log.error("恢复 Managed 文件失败 source={} trash={}", file.sourcePath(), file.trashPath(), failure);
            }
        }
        return success;
    }

    private String writeManifest(List<StagedFile> files) {
        try {
            return mapper.writeValueAsString(files);
        } catch (JsonProcessingException failure) {
            throw new ApiException("DELETE_OPERATION_INVALID", "无法记录删除文件清单");
        }
    }

    private List<StagedFile> readManifest(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException failure) {
            throw new ApiException("DELETE_OPERATION_INVALID", "删除文件清单损坏，已停止自动处理");
        }
    }

    private static ApiException invalidPath(String message) {
        return new ApiException("INVALID_LIBRARY_PATH", message);
    }

    private record StagedFile(String sourcePath, String trashPath) {}
}
