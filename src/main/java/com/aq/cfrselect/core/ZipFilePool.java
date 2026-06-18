package com.aq.cfrselect.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reusable {@link ZipFile} cache keyed by archive path.
 *
 * <p>Creating a {@code ZipFile} requires reading the entire central directory,
 * which is expensive. This pool keeps a single open {@code ZipFile} per archive
 * path and shares it across all tasks via reference counting. When the last
 * consumer releases, the underlying {@code ZipFile} is closed.</p>
 */
final class ZipFilePool implements Closeable {

    private final ConcurrentMap<Path, PooledZipFile> pool = new ConcurrentHashMap<>();

    /**
     * Acquire a pooled handle for the given archive path.
     * The returned handle must be closed when finished.
     */
    PooledZipFile acquire(Path archive) {
        PooledZipFile existing;
        for (;;) {
            existing = pool.get(archive);
            if (existing != null) {
                if (existing.tryInc()) {
                    return existing;
                }
                // entry was concurrently closed, retry
                continue;
            }
            try {
                ZipFile zf = new ZipFile(archive.toFile());
                PooledZipFile created = new PooledZipFile(archive, zf, pool);
                PooledZipFile prev = pool.putIfAbsent(archive, created);
                if (prev == null) {
                    return created;
                }
                // another thread won
                created.decAndCloseIfZero();
            } catch (IOException e) {
                throw new RuntimeException("Failed to open archive: " + archive, e);
            }
        }
    }

    /** Close all cached {@code ZipFile} entries, regardless of ref-count. */
    @Override
    public void close() {
        for (PooledZipFile entry : pool.values()) {
            entry.forceClose();
        }
        pool.clear();
    }

    static final class PooledZipFile implements Closeable {
        private final Path archive;
        private final ZipFile zipFile;
        private final ConcurrentMap<Path, PooledZipFile> pool;
        private final AtomicInteger refCount = new AtomicInteger(1);

        private PooledZipFile(Path archive, ZipFile zipFile,
                             ConcurrentMap<Path, PooledZipFile> pool) {
            this.archive = archive;
            this.zipFile = zipFile;
            this.pool = pool;
        }

        private synchronized boolean tryInc() {
            if (zipFile == null) return false; // already closed
            refCount.incrementAndGet();
            return true;
        }

        ZipEntry getEntry(String name) {
            return zipFile.getEntry(name);
        }

        java.io.InputStream getInputStream(ZipEntry entry) throws IOException {
            return zipFile.getInputStream(entry);
        }

        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries() {
            return zipFile.entries();
        }

        @Override
        public void close() {
            decAndCloseIfZero();
        }

        private void decAndCloseIfZero() {
            if (refCount.decrementAndGet() == 0) {
                forceClose();
            }
        }

        private synchronized void forceClose() {
            if (zipFile != null) {
                try {
                    ((ZipFile) zipFile).close();
                } catch (IOException ignored) {
                    // best effort
                }
                pool.remove(archive, this);
            }
        }
    }
}