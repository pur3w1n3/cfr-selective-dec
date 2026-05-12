package com.aq.cfrselect.matching;

import com.aq.cfrselect.archive.ArchiveNames;
import com.aq.cfrselect.cli.UsageException;
import com.aq.cfrselect.model.ClassFileMatch;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class PackageMatcher {
    private final List<String> prefixes;

    public PackageMatcher(List<String> packages) {
        this.prefixes = packages.stream()
                .map(PackageMatcher::normalizePackage)
                .filter(new java.util.function.Predicate<String>() {
                    @Override
                    public boolean test(String s) {
                        return s != null && !s.trim().isEmpty();
                    }
                })
                .distinct()
                .collect(Collectors.toList());
        if (this.prefixes.isEmpty()) {
            throw new UsageException("No valid packages specified.");
        }
    }

    public boolean matchesClassEntry(String entryName) {
        String normalized = ArchiveNames.normalizeZipName(entryName);
        if (!normalized.endsWith(".class")) {
            return false;
        }
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    public ClassFileMatch matchClassFile(Path root, Path classFile) {
        String normalized = ArchiveNames.normalizeZipName(root.relativize(classFile).toString());
        if (!normalized.endsWith(".class")) {
            return null;
        }

        ClassFileMatch webClasses = matchKnownClassesPrefix(classFile, normalized, ArchiveNames.WEB_CLASSES);
        if (webClasses != null) {
            return webClasses;
        }
        ClassFileMatch bootClasses = matchKnownClassesPrefix(classFile, normalized, ArchiveNames.BOOT_CLASSES);
        if (bootClasses != null) {
            return bootClasses;
        }

        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix + "/")) {
                return new ClassFileMatch(classFile, "", normalized);
            }

            String marker = "/" + prefix + "/";
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                String rootName = normalized.substring(0, index);
                String entryName = normalized.substring(index + 1);
                return new ClassFileMatch(classFile, rootName, entryName);
            }
        }
        return null;
    }

    private ClassFileMatch matchKnownClassesPrefix(Path classFile, String normalized, String knownPrefix) {
        int index = normalized.indexOf(knownPrefix);
        if (index < 0) {
            return null;
        }
        String entryName = normalized.substring(index + knownPrefix.length());
        if (!matchesClassEntry(entryName)) {
            return null;
        }
        String rootName = normalized.substring(0, index + knownPrefix.length() - 1);
        return new ClassFileMatch(classFile, rootName, entryName);
    }

    private static String normalizePackage(String packageName) {
        String result = Objects.requireNonNull(packageName).trim();
        if (result.endsWith(".*")) {
            result = result.substring(0, result.length() - 2);
        }
        result = result.replace('.', '/').replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
