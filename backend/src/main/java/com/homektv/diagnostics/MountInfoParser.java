package com.homektv.diagnostics;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Structural parser for Linux /proc/self/mountinfo. It never probes by writing. */
public final class MountInfoParser {

    public enum Status { READ_ONLY, READ_WRITE, UNKNOWN }

    public record Inspection(Status status, String mountPoint, String fileSystem) {
        static Inspection unknown() {
            return new Inspection(Status.UNKNOWN, null, null);
        }
    }

    private record Entry(String mountPoint, List<String> mountOptions, String fileSystem) {}

    private MountInfoParser() {}

    public static Inspection inspect(List<String> lines, String targetPath) {
        if (lines == null || targetPath == null || targetPath.isBlank()) {
            return Inspection.unknown();
        }
        String target = normalize(targetPath);
        Optional<Entry> selected = lines.stream()
                .map(MountInfoParser::parse)
                .flatMap(Optional::stream)
                .filter(entry -> contains(entry.mountPoint(), target))
                .max(Comparator.comparingInt(entry -> entry.mountPoint().length()));
        if (selected.isEmpty()) {
            return Inspection.unknown();
        }
        Entry entry = selected.get();
        Status status = entry.mountOptions().contains("ro")
                ? Status.READ_ONLY
                : entry.mountOptions().contains("rw") ? Status.READ_WRITE : Status.UNKNOWN;
        return new Inspection(status, entry.mountPoint(), entry.fileSystem());
    }

    private static Optional<Entry> parse(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        String[] fields = line.trim().split(" +");
        int separator = -1;
        for (int i = 6; i < fields.length; i++) {
            if ("-".equals(fields[i])) {
                separator = i;
                break;
            }
        }
        if (fields.length < 10 || separator < 6 || separator + 2 >= fields.length) {
            return Optional.empty();
        }
        String mountPoint = normalize(decode(fields[4]));
        List<String> options = List.of(fields[5].split(","));
        return Optional.of(new Entry(mountPoint, options, fields[separator + 1]));
    }

    private static boolean contains(String mountPoint, String target) {
        return "/".equals(mountPoint) || target.equals(mountPoint)
                || target.startsWith(mountPoint + "/");
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String decode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\\' && i + 3 < value.length()
                    && isOctal(value.charAt(i + 1))
                    && isOctal(value.charAt(i + 2))
                    && isOctal(value.charAt(i + 3))) {
                int code = Integer.parseInt(value.substring(i + 1, i + 4), 8);
                decoded.append((char) code);
                i += 3;
            } else {
                decoded.append(value.charAt(i));
            }
        }
        return decoded.toString();
    }

    private static boolean isOctal(char value) {
        return value >= '0' && value <= '7';
    }
}
