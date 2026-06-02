package com.aq.cfrselect.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class DirectoryInputSource implements InputSource {
    private final Path path;

    DirectoryInputSource(Path path) {
        this.path = path;
    }

    @Override
    public InputStream open() throws IOException {
        return Files.newInputStream(path);
    }
}
