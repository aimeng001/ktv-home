package com.homektv.diagnostics;

import com.homektv.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only NAS mount inspection backed by Linux mountinfo. */
@Component
public class NasMountInspector {

    private static final Path PROC_MOUNTINFO = Path.of("/proc/self/mountinfo");
    private final AppProperties properties;

    public NasMountInspector(AppProperties properties) {
        this.properties = properties;
    }

    public MountInfoParser.Inspection inspectConfiguredLibrary() {
        if (!properties.isExternalReadOnly() || !Files.isRegularFile(PROC_MOUNTINFO)) {
            return MountInfoParser.Inspection.unknown();
        }
        try {
            return MountInfoParser.inspect(
                    Files.readAllLines(PROC_MOUNTINFO, StandardCharsets.UTF_8),
                    properties.getSourceLibraryPath());
        } catch (IOException | SecurityException ignored) {
            return MountInfoParser.Inspection.unknown();
        }
    }
}
