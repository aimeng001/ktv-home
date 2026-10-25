package com.homektv.web.dto;

import com.homektv.domain.MediaImportRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MediaImportRecordDtoTest {

    @Test
    void mapsEveryImportActionToItsDisplayStatus() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("COPIED", "AUTO_COPIED"),
                Map.entry("TRANSCODED", "TRANSCODED"),
                Map.entry("PENDING_TRANSCODE", "PENDING_TRANSCODE"),
                Map.entry("SKIPPED_SOURCE_MD5_DUPLICATE", "DUPLICATE"),
                Map.entry("SKIPPED_OUTPUT_MD5_DUPLICATE", "DUPLICATE"),
                Map.entry("SKIPPED_DUPLICATE", "DUPLICATE"),
                Map.entry("UNRECOGNIZED", "UNRECOGNIZED"),
                Map.entry("FAILED", "FAILED"),
                Map.entry("RECOVERY_REQUIRED", "RECOVERY_REQUIRED"));

        Map<String, String> actual = expected.keySet().stream().collect(Collectors.toMap(
                action -> action,
                action -> {
                    MediaImportRecord record = new MediaImportRecord();
                    record.setAction(action);
                    return MediaImportRecordDto.from(record).displayStatus();
                }));

        assertThat(actual).isEqualTo(expected);
    }
}
