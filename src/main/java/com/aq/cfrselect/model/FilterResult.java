package com.aq.cfrselect.model;

import java.nio.file.Path;

public final class FilterResult {
    public final Path filteredJar;
    public final int classCount;

    public FilterResult(Path filteredJar, int classCount) {
        this.filteredJar = filteredJar;
        this.classCount = classCount;
    }
}
