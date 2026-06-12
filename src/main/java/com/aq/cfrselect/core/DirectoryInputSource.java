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

    @Override
    public InputSource sibling(String siblingEntryName) {
        int slash = siblingEntryName.lastIndexOf('/');
        String filename = slash >= 0 ? siblingEntryName.substring(slash + 1) : siblingEntryName;
        Path siblingPath = path.resolveSibling(filename);
        return Files.isRegularFile(siblingPath) ? new DirectoryInputSource(siblingPath) : null;
    }
}
