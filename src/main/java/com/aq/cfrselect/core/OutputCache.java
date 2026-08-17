package com.aq.cfrselect.core;

import com.aq.cfrselect.cli.CliOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fingerprint-backed cache for completed source outputs. */
final class OutputCache {
    private final Path cacheFile;
    private final CliOptions options;
    private final Properties previous = new Properties();
    private final Properties current = new Properties();
    private final Set<Path> completed = Collections.newSetFromMap(
            new ConcurrentHashMap<Path, Boolean>());

    OutputCache(Path cacheFile, CliOptions options) {
        this.cacheFile = cacheFile.toAbsolutePath().normalize();
        this.options = options;
        if (Files.isRegularFile(this.cacheFile)) {
            try (InputStream in = Files.newInputStream(this.cacheFile)) {
                previous.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                previous.clear();
            }
        }
    }

    boolean tryReuse(DecompileUnit unit) {
        Path target = unit.outputTarget();
        if (!isNonEmptyFile(target)) return false;
        try {
            String fingerprint = unit.fingerprint(options);
            if (!fingerprint.equals(previous.getProperty(cacheKey(target)))) return false;
            current.setProperty(cacheKey(target), fingerprint);
            completed.add(target);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    void record(DecompileUnit unit) {
        Path target = unit.outputTarget();
        completed.add(target);
        try {
            current.setProperty(cacheKey(target), unit.fingerprint(options));
        } catch (IOException ignored) {
            // The output remains valid for this run; it will simply be rebuilt next time.
        }
    }

    boolean isCompleted(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        return completed.contains(normalized) && isNonEmptyFile(normalized);
    }

    void write() throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path temporary = cacheFile.resolveSibling("." + cacheFile.getFileName()
                + ".tmp-" + System.nanoTime());
        IOException failure = null;
        try {
            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                current.store(out, "cfr-selective-dec output fingerprints");
            }
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private String cacheKey(Path target) {
        return target.toAbsolutePath().normalize().toString();
    }

    private boolean isNonEmptyFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0L;
        } catch (IOException e) {
            return false;
        }
    }
}
