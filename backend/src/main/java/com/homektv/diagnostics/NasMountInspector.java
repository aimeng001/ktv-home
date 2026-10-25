package com.homektv.diagnostics;

import com.homektv.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

/** Read-only NAS mount inspection backed by Linux mountinfo. */
@Component
public class NasMountInspector {

    private static final Path PROC_MOUNTINFO = Path.of("/proc/self/mountinfo");
    private final AppProperties properties;
    private final Path mountInfoPath;
    private final MountInfoReader mountInfoReader;

    @Autowired
    public NasMountInspector(AppProperties properties) {
        this(properties, PROC_MOUNTINFO,
                path -> Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    NasMountInspector(AppProperties properties, Path mountInfoPath, MountInfoReader mountInfoReader) {
        this.properties = properties;
        this.mountInfoPath = mountInfoPath;
        this.mountInfoReader = mountInfoReader;
    }

    public MountInfoParser.Inspection inspectConfiguredLibrary() {
        if (!properties.isExternalReadOnly() || !Files.isRegularFile(mountInfoPath)) {
            return MountInfoParser.Inspection.unknown();
        }
        try {
            return MountInfoParser.inspect(
                    mountInfoReader.read(mountInfoPath),
                    properties.getSourceLibraryPath());
        } catch (IOException | SecurityException ignored) {
            return MountInfoParser.Inspection.unknown();
        }
    }

    @FunctionalInterface
    interface MountInfoReader {
        List<String> read(Path path) throws IOException;
    }
}
