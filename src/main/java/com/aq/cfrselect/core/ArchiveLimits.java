package com.aq.cfrselect.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class ArchiveLimits {
    static final int MAX_NESTED_DEPTH = 16;
    static final int MAX_NESTED_ARCHIVES = 10_000;
    static final long MAX_TARGET_CLASSES = 1_000_000L;
    static final long MAX_NESTED_EXTRACTED_BYTES = 8L * 1024L * 1024L * 1024L;
    static final long MAX_CLASS_BYTES = 64L * 1024L * 1024L;

    private ArchiveLimits() {
    }

    static long copyLimited(InputStream in, OutputStream out, long limit, String source)
            throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Extraction limit exceeded for " + source
                        + ": limit=" + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }
}
