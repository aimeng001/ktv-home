package com.homektv.contract;

import com.homektv.domain.MediaImportRecord;

import java.time.OffsetDateTime;

/**
 * 媒体导入记录的数据传输对象，封装了导入记录的完整信息及其显示状态转换逻辑。
 *
 * Data Transfer Object for media import records, encapsulating full import record
 * information along with display status transformation logic.
 */
public record MediaImportRecordDto(
        Long id,
        String sourcePath,
        String sourceFilename,
        String sourceMd5,
        String parsedTitle,
        String parsedArtist,
        String mediaType,
        String sourceFormat,
        String outputPath,
        String outputMd5,
        String outputFormat,
        String videoCodec,
        String audioCodec,
        String action,
        String displayStatus,
        String reason,
        Long songId,
        Long songFileId,
        boolean transcodeRequired,
        boolean duplicate,
        boolean imported,
        boolean sourceDeleted,
        OffsetDateTime createdAt,
        boolean deleteSourceRequested,
        String cleanupStatus,
        String cleanupError
) {
    /**
     * 将 {@link MediaImportRecord} 实体转换为 DTO，同时完成显示状态的映射。
     *
     * Converts a {@link MediaImportRecord} entity to a DTO, performing display
     * status mapping at the same time.
     *
     * @param record 媒体导入记录实体 / the media import record entity
     * @return 填充了转换后显示状态的 DTO 实例 / DTO instance with mapped display status
     */
    public static MediaImportRecordDto from(MediaImportRecord record) {
        return new MediaImportRecordDto(
                record.getId(),
                record.getSourcePath(),
                record.getSourceFilename(),
                record.getSourceMd5(),
                record.getParsedTitle(),
                record.getParsedArtist(),
                record.getMediaType(),
                record.getSourceFormat(),
                record.getOutputPath(),
                record.getOutputMd5(),
                record.getOutputFormat(),
                record.getVideoCodec(),
                record.getAudioCodec(),
                record.getAction(),
                displayStatus(record),
                record.getReason(),
                record.getSongId(),
                record.getSongFileId(),
                record.isTranscodeRequired(),
                record.isDuplicateFlag(),
                record.isImportedFlag(),
                record.isSourceDeleted(),
                record.getCreatedAt(),
                record.isDeleteSourceRequested(),
                record.getCleanupStatus(),
                record.getCleanupError()
        );
    }

    private static String displayStatus(MediaImportRecord record) {
        return switch (record.getAction()) {
            case "COPIED" -> "AUTO_COPIED";
            case "TRANSCODED" -> "TRANSCODED";
            case "PENDING_TRANSCODE" -> "PENDING_TRANSCODE";
            case "SKIPPED_SOURCE_MD5_DUPLICATE", "SKIPPED_OUTPUT_MD5_DUPLICATE", "SKIPPED_DUPLICATE" -> "DUPLICATE";
            case "UNRECOGNIZED" -> "UNRECOGNIZED";
            case "FAILED" -> "FAILED";
            default -> record.getAction();
        };
    }
}
