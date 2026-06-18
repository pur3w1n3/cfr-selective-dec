package com.aq.cfrselect.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;

final class ZipInputSource implements InputSource {
    final Path archive;
    private final String entryName;
    // Prefix stripped by mapJarClassEntry (e.g. "BOOT-INF/classes/"), empty if none
    final String entryPrefix;

    ZipInputSource(Path archive, String entryName) {
        this.archive = archive;
        this.entryName = entryName;
        this.entryPrefix = entryPrefix(entryName);
    }

    private static String entryPrefix(String entryName) {
        if (entryName.startsWith("BOOT-INF/classes/")) return "BOOT-INF/classes/";
        if (entryName.startsWith("WEB-INF/classes/")) return "WEB-INF/classes/";
        return "";
    }

    /**
     * Open an input stream for this entry using the shared ZipFile pool.
     * The pool avoids repeated central directory reads for the same archive.
     */
    InputStream open(ZipFilePool pool) throws IOException {
        ZipFilePool.PooledZipFile pzf = pool.acquire(archive);
        ZipEntry entry = pzf.getEntry(entryName);
        if (entry == null) {
            pzf.close();
            throw new IOException("Missing zip entry: " + entryName + " in " + archive);
        }
        final InputStream delegate = pzf.getInputStream(entry);
        return new InputStream() {
            private boolean closed = false;

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
                if (closed) return;
                closed = true;
                try {
                    delegate.close();
                } finally {
                    pzf.close(); // release pooled handle
                }
            }
        };
    }

    /**
     * Check whether a sibling entry exists in the same archive using the pool.
     */
    InputSource sibling(String siblingEntryName, ZipFilePool pool) {
        String actual = entryPrefix + siblingEntryName;
        try (ZipFilePool.PooledZipFile pzf = pool.acquire(archive)) {
            return pzf.getEntry(actual) != null ? new ZipInputSource(archive, actual) : null;
        }
    }

    /**
     * Legacy no-pool methods kept for backward compatibility with InputSource interface.
     * Prefer using the pool-based overloads where a pool is available.
     */
    @Override
    public InputStream open() throws IOException {
        final java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(archive.toFile());
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

    @Override
    public InputSource sibling(String siblingEntryName) {
        String actual = entryPrefix + siblingEntryName;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(archive.toFile())) {
            return zf.getEntry(actual) != null ? new ZipInputSource(archive, actual) : null;
        } catch (IOException e) {
            return null;
        }
    }
}