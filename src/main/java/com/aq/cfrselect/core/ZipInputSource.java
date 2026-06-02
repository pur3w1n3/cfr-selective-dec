package com.aq.cfrselect.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ZipInputSource implements InputSource {
    private final Path archive;
    private final String entryName;

    ZipInputSource(Path archive, String entryName) {
        this.archive = archive;
        this.entryName = entryName;
    }

    @Override
    public InputStream open() throws IOException {
        final ZipFile zipFile = new ZipFile(archive.toFile());
        final ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            zipFile.close();
            throw new IOException("Missing zip entry: " + entryName + " in " + archive);
        }
        final InputStream delegate = zipFile.getInputStream(entry);
        return new InputStream() {
            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return delegate.read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                try {
                    delegate.close();
                } finally {
                    zipFile.close();
                }
            }
        };
    }
}
