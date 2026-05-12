package com.aq.cfrselect.model;

import com.aq.cfrselect.archive.ArchiveNames;

import java.nio.file.Path;

public final class ClassFileMatch implements Comparable<ClassFileMatch> {
    public final Path path;
    public final String rootName;
    public final String entryName;

    public ClassFileMatch(Path path, String rootName, String entryName) {
        this.path = path;
        this.rootName = ArchiveNames.normalizeZipName(rootName);
        this.entryName = ArchiveNames.normalizeZipName(entryName);
    }

    @Override
    public int compareTo(ClassFileMatch other) {
        int rootCompare = this.rootName.compareTo(other.rootName);
        if (rootCompare != 0) {
            return rootCompare;
        }
        return this.entryName.compareTo(other.entryName);
    }
}
