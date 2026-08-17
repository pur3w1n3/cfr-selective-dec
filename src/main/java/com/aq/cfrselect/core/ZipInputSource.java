package com.aq.cfrselect.core;

import com.aq.cfrselect.archive.ArchiveNames;

import java.io.IOException;
import java.nio.file.Path;

final class ZipInputSource implements InputSource {
    final Path archive;
    final String entryName;
    final long crc;
    final long size;
    final String containerFingerprint;
    // Prefix stripped by mapJarClassEntry (e.g. "BOOT-INF/classes/"), empty if none
    final String entryPrefix;

    ZipInputSource(Path archive, String entryName, long crc, long size,
                   String containerFingerprint) {
        this.archive = archive.toAbsolutePath().normalize();
        this.entryName = entryName;
        this.crc = crc;
        this.size = size;
        this.containerFingerprint = containerFingerprint;
        this.entryPrefix = entryPrefix(entryName);
    }

    private static String entryPrefix(String entryName) {
        if (entryName.startsWith(ArchiveNames.BOOT_CLASSES)) return ArchiveNames.BOOT_CLASSES;
        if (entryName.startsWith(ArchiveNames.WEB_CLASSES)) return ArchiveNames.WEB_CLASSES;
        return "";
    }

    @Override
    public Path directClassFile() {
        return null;
    }

    @Override
    public Path classPathRoot() {
        return null;
    }

    @Override
    public String sourceKey() {
        return "zip:" + archive;
    }

    @Override
    public String fingerprint() throws IOException {
        return "zip|" + containerFingerprint + "|" + entryName + "|" + crc + "|" + size;
    }
}
