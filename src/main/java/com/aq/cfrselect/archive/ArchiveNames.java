package com.aq.cfrselect.archive;

import java.nio.file.Path;
import java.util.Locale;

public final class ArchiveNames {
    public static final String WEB_CLASSES = "WEB-INF/classes/";
    public static final String WEB_LIB = "WEB-INF/lib/";
    public static final String BOOT_CLASSES = "BOOT-INF/classes/";
    public static final String DEFAULT_OUTPUT_ENCODING = "UTF-8";

    private ArchiveNames() {
    }

    public static boolean isSupportedTopLevelArchive(Path path) {
        String ext = extension(path);
        return ".jar".equals(ext) || ".war".equals(ext);
    }

    public static boolean isNestedArchive(String name) {
        String lower = normalizeZipName(name).toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".war");
    }

    public static String extension(Path path) {
        return extension(path.getFileName().toString());
    }

    public static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index).toLowerCase(Locale.ROOT) : "";
    }

    public static String stripExtension(String name) {
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(0, index) : name;
    }

    public static String sourceName(Path path) {
        return path.getFileName().toString();
    }

    public static String normalizeZipName(String name) {
        return name.replace('\\', '/');
    }

    public static String mapJarClassEntry(String name) {
        String normalized = normalizeZipName(name);
        if (normalized.startsWith(BOOT_CLASSES)) {
            return normalized.substring(BOOT_CLASSES.length());
        }
        if (normalized.startsWith(WEB_CLASSES)) {
            return normalized.substring(WEB_CLASSES.length());
        }
        return normalized;
    }

    public static String safeFileName(String name) {
        String normalized = normalizeZipName(name);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    public static String safeArchiveOutputName(String name) {
        String result = normalizeZipName(name)
                .replaceAll("^[A-Za-z]:", "")
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]+", "_")
                .replaceAll("^\\.+", "_");
        if (result.isEmpty()) {
            return "archive";
        }
        if (result.length() > 180) {
            return result.substring(0, 180);
        }
        return result;
    }

    public static boolean isSafeJarEntryName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = normalizeZipName(name);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.indexOf('\0') >= 0 || normalized.startsWith("/") || normalized.startsWith("\\")) {
            return false;
        }
        if (normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    public static String requireSafeJarEntryName(String name, String source) throws SecurityException {
        String normalized = normalizeZipName(name);
        if (!isSafeJarEntryName(normalized)) {
            throw new SecurityException("Unsafe archive entry name in " + source + ": " + name);
        }
        return normalized;
    }
}
