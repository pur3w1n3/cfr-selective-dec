package com.aq.cfrselect.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DirectoryInputSource implements InputSource {
    private final Path path;
    private final Path classPathRoot;
    private final String containerFingerprint;

    DirectoryInputSource(Path path, String entryName, String containerFingerprint) {
        this.path = path.toAbsolutePath().normalize();
        Path root = this.path;
        int segmentCount = 1;
        for (int i = 0; i < entryName.length(); i++) {
            if (entryName.charAt(i) == '/') {
                segmentCount++;
            }
        }
        for (int i = 0; i < segmentCount; i++) {
            root = root.getParent();
            if (root == null) {
                throw new IllegalArgumentException("Class entry does not fit path: " + entryName + " -> " + path);
            }
        }
        this.classPathRoot = root;
        this.containerFingerprint = containerFingerprint;
    }

    @Override
    public Path directClassFile() {
        return path;
    }

    @Override
    public Path classPathRoot() {
        return classPathRoot;
    }

    @Override
    public String sourceKey() {
        return "dir:" + classPathRoot;
    }

    @Override
    public String fingerprint() throws IOException {
        return "dir|" + path + "|" + String.valueOf(containerFingerprint) + "|"
                + Files.size(path) + "|"
                + Files.getLastModifiedTime(path).toMillis();
    }
}
