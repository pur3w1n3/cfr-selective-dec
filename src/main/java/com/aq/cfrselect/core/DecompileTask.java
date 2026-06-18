package com.aq.cfrselect.core;

import java.nio.file.Path;

final class DecompileTask {
    final String displayName;
    final Path outputDir;
    final String entryName;
    final String className;
    final String sourceLocation;
    final InputSource inputSource;

    DecompileTask(String displayName, Path outputDir, String entryName,
                  String sourceLocation, InputSource inputSource) {
        this.displayName = displayName;
        this.outputDir = outputDir;
        this.entryName = entryName;
        this.className = DecompileUtils.toClassName(entryName);
        this.sourceLocation = sourceLocation;
        this.inputSource = inputSource;
    }

}
