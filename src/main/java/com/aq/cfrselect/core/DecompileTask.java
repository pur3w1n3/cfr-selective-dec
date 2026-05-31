package com.aq.cfrselect.core;

import java.nio.file.Path;

final class DecompileTask {
    final String displayName;
    final Path outputDir;
    final String entryName;
    final InputSource inputSource;

    DecompileTask(String displayName, Path outputDir, String entryName, InputSource inputSource) {
        this.displayName = displayName;
        this.outputDir = outputDir;
        this.entryName = entryName;
        this.inputSource = inputSource;
    }
}
