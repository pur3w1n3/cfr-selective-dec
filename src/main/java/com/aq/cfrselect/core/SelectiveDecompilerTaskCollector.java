package com.aq.cfrselect.core;

import com.aq.cfrselect.archive.ArchiveNames;
import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.matching.PackageMatcher;
import com.aq.cfrselect.model.ClassFileMatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class SelectiveDecompilerTaskCollector {
    private static final AtomicLong nestedSeq = new AtomicLong();
    private static final Comparator<DecompileTask> BY_DISPLAY_NAME = new Comparator<DecompileTask>() {
        @Override
        public int compare(DecompileTask a, DecompileTask b) {
            return a.displayName.compareTo(b.displayName);
        }
    };

    private final CliOptions options;
    private final PackageMatcher matcher;
    private final Path tempRoot;
    private final SelectiveDecompilerSummary summary;
    private final ConcurrentLinkedQueue<DecompileTask> collected = new ConcurrentLinkedQueue<DecompileTask>();
    private final AtomicLong matchedTargetClasses = new AtomicLong();
    private final AtomicInteger nestedArchiveCount = new AtomicInteger();
    private final AtomicLong nestedExtractedBytes = new AtomicLong();
    private final Object extractLock = new Object();

    SelectiveDecompilerTaskCollector(CliOptions options, PackageMatcher matcher,
                                     Path tempRoot, SelectiveDecompilerSummary summary) {
        this.options = options;
        this.matcher = matcher;
        this.tempRoot = tempRoot;
        this.summary = summary;
    }

    List<DecompileTask> collect() throws IOException, InterruptedException {
        if (Files.isDirectory(options.input)) {
            processDirectory(options.input, options.output.resolve(ArchiveNames.sourceName(options.input)));
        } else {
            processArchive(options.input, options.output, ArchiveNames.sourceName(options.input),
                    options.input.toAbsolutePath().normalize().toString(), 0,
                    archiveFingerprint(options.input), true);
        }
        List<DecompileTask> tasks = new ArrayList<DecompileTask>(collected);
        Collections.sort(tasks, BY_DISPLAY_NAME);
        List<DecompileTask> isolatedTasks = isolateOutputNamespaces(tasks);
        List<DecompileTask> uniqueTasks = deduplicateByOutputTarget(isolatedTasks);
        Collections.sort(uniqueTasks, BY_DISPLAY_NAME);
        summary.matchedClasses.set(uniqueTasks.size());
        return uniqueTasks;
    }

    private List<DecompileTask> deduplicateByOutputTarget(List<DecompileTask> tasks) {
        Map<String, DecompileTask> unique = new LinkedHashMap<String, DecompileTask>();
        for (DecompileTask task : tasks) {
            String key = outputTargetKey(task);
            DecompileTask existing = unique.get(key);
            if (existing == null) {
                unique.put(key, task);
                continue;
            }
            summary.duplicateUnits.incrementAndGet();
            summary.duplicateClasses.add(task.displayName + " -> " + existing.displayName);
        }
        return new ArrayList<DecompileTask>(unique.values());
    }

    private List<DecompileTask> isolateOutputNamespaces(List<DecompileTask> tasks) {
        Map<String, List<DecompileTask>> sourceGroups = new LinkedHashMap<String, List<DecompileTask>>();
        for (DecompileTask task : tasks) {
            String key = task.inputSource.sourceKey() + "\u0000"
                    + task.outputDir.toAbsolutePath().normalize();
            List<DecompileTask> group = sourceGroups.get(key);
            if (group == null) {
                group = new ArrayList<DecompileTask>();
                sourceGroups.put(key, group);
            }
            group.add(task);
        }

        Map<String, String> allocatedRoots = new LinkedHashMap<String, String>();
        List<DecompileTask> isolated = new ArrayList<DecompileTask>(tasks.size());
        for (Map.Entry<String, List<DecompileTask>> sourceGroup : sourceGroups.entrySet()) {
            List<DecompileTask> group = sourceGroup.getValue();
            Path desiredRoot = group.get(0).outputDir.toAbsolutePath().normalize();
            Path allocatedRoot = allocateOutputRoot(desiredRoot, group.get(0).inputSource,
                    sourceGroup.getKey(), allocatedRoots);
            for (DecompileTask task : group) {
                Path relative = desiredRoot.relativize(task.outputDir.toAbsolutePath().normalize());
                isolated.add(copyWithOutputDir(task, allocatedRoot.resolve(relative)));
            }
        }
        return isolateCaseInsensitiveTargets(isolated);
    }

    private Path allocateOutputRoot(Path desiredRoot, InputSource source, String sourceKey,
                                    Map<String, String> allocatedRoots) {
        String desiredKey = fileSystemKey(desiredRoot);
        String existingSource = allocatedRoots.get(desiredKey);
        if (existingSource == null || existingSource.equals(sourceKey)) {
            allocatedRoots.put(desiredKey, sourceKey);
            return desiredRoot;
        }

        String suffix = source instanceof ZipInputSource
                ? ArchiveNames.extension(((ZipInputSource) source).archive).replace(".", "")
                : "classes";
        if (suffix.isEmpty()) suffix = "source";
        String baseName = desiredRoot.getFileName().toString() + "-" + suffix;
        Path parent = desiredRoot.getParent();
        Path candidate = parent.resolve(baseName);
        int attempt = 2;
        while (allocatedRoots.containsKey(fileSystemKey(candidate))) {
            candidate = parent.resolve(baseName + "-" + attempt++);
        }
        allocatedRoots.put(fileSystemKey(candidate), sourceKey);
        return candidate;
    }

    private List<DecompileTask> isolateCaseInsensitiveTargets(List<DecompileTask> tasks) {
        if (java.io.File.separatorChar != '\\') return tasks;
        Map<String, String> targets = new LinkedHashMap<String, String>();
        List<DecompileTask> result = new ArrayList<DecompileTask>(tasks.size());
        int conflictSequence = 0;
        for (DecompileTask task : tasks) {
            Path target = task.outputDir.resolve(DecompileUtils.toJavaEntry(task.entryName))
                    .toAbsolutePath().normalize();
            String key = fileSystemKey(target);
            String exact = target.toString();
            String existing = targets.get(key);
            if (existing != null && !existing.equals(exact)) {
                Path conflictRoot = task.outputDir.resolve("__case_conflicts__")
                        .resolve("conflict-" + (++conflictSequence));
                task = copyWithOutputDir(task, conflictRoot);
                target = conflictRoot.resolve(DecompileUtils.toJavaEntry(task.entryName))
                        .toAbsolutePath().normalize();
                key = fileSystemKey(target);
                exact = target.toString();
            }
            targets.putIfAbsent(key, exact);
            result.add(task);
        }
        return result;
    }

    private DecompileTask copyWithOutputDir(DecompileTask task, Path outputDir) {
        return new DecompileTask(task.displayName, outputDir, task.entryName,
                task.sourceLocation, task.inputSource, task.outerEntryName);
    }

    private String fileSystemKey(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return java.io.File.separatorChar == '\\' ? value.toLowerCase(Locale.ROOT) : value;
    }

    private String outputTargetKey(DecompileTask task) {
        String javaEntry = DecompileUtils.toJavaEntry(task.entryName);
        return task.outputDir.resolve(javaEntry).toAbsolutePath().normalize().toString();
    }

    private void processDirectory(Path inputDir, Path outputDir)
            throws IOException, InterruptedException {
        List<Path> classFilesRaw = new ArrayList<Path>();
        List<Path> archives = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(inputDir)) {
            walk.forEach(path -> {
                if (!Files.isRegularFile(path) || isUnderOutput(path)) {
                    return;
                }
                String name = path.getFileName().toString().toLowerCase();
                if (name.endsWith(".class")) {
                    classFilesRaw.add(path);
                } else if (ArchiveNames.isSupportedTopLevelArchive(path)) {
                    archives.add(path);
                }
            });
        }
        Collections.sort(classFilesRaw);
        Collections.sort(archives);
        String directoryFingerprint = directoryFingerprint(inputDir, classFilesRaw);

        List<ClassFileMatch> classFiles = new ArrayList<ClassFileMatch>();
        for (Path path : classFilesRaw) {
            ClassFileMatch match = matcher.matchClassFile(inputDir, path);
            if (match != null) {
                classFiles.add(match);
            }
        }
        Collections.sort(classFiles);

        for (ClassFileMatch classFile : classFiles) {
            checkInterrupted();
            String displayName = classFile.rootName.isEmpty()
                    ? "classes!" + classFile.entryName
                    : classFile.rootName + "!" + classFile.entryName;
            Path taskOutputDir = classFile.rootName.isEmpty() ? outputDir : outputDir.resolve(classFile.rootName);
            addTask(new DecompileTask(displayName, taskOutputDir, classFile.entryName,
                    classFile.path.toAbsolutePath().normalize().toString(),
                    new DirectoryInputSource(classFile.path, classFile.entryName, directoryFingerprint),
                    readOuterEntry(classFile.path, classFile.entryName)));
        }

        scanTopLevelArchives(inputDir, outputDir, archives);
    }

    private void scanTopLevelArchives(Path inputDir, Path outputDir, List<Path> archives)
            throws IOException, InterruptedException {
        if (archives.isEmpty()) {
            return;
        }
        if (archives.size() == 1) {
            Path archive = archives.get(0);
            String displayName = ArchiveNames.normalizeZipName(inputDir.relativize(archive).toString());
            processArchive(archive, outputDir, displayName,
                    archive.toAbsolutePath().normalize().toString(), 0,
                    archiveFingerprint(archive), true);
            return;
        }

        int threads = Math.max(1, Math.min(options.threads, archives.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> jobs = new ArrayList<Callable<Void>>(archives.size());
            for (final Path archive : archives) {
                final String displayName = ArchiveNames.normalizeZipName(
                        inputDir.relativize(archive).toString());
                final String sourceLabel = archive.toAbsolutePath().normalize().toString();
                final String fingerprint = archiveFingerprint(archive);
                jobs.add(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        processArchive(archive, outputDir, displayName, sourceLabel, 0, fingerprint, true);
                        return null;
                    }
                });
            }
            List<Future<Void>> futures = pool.invokeAll(jobs);
            unwrapScanFutures(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    private void unwrapScanFutures(List<Future<Void>> futures) throws IOException, InterruptedException {
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof InterruptedException) throw (InterruptedException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IOException("Top-level archive scan failed", cause);
            }
        }
    }

    private int processArchive(Path archive, Path outputBase, String displayName,
                               String sourceArchiveLabel, int depth, String archiveFingerprint,
                               boolean processNested)
            throws IOException, InterruptedException {
        return processArchive(archive, outputBase, displayName, sourceArchiveLabel, depth,
                archiveFingerprint, processNested, Collections.<String>emptySet());
    }

    private int processArchive(Path archive, Path outputBase, String displayName,
                               String sourceArchiveLabel, int depth, String archiveFingerprint,
                               boolean processNested, Set<String> skipNestedEntries)
            throws IOException, InterruptedException {
        Path outputDir = outputBase.resolve(ArchiveNames.stripExtension(displayName));
        return scanDiskArchive(archive, outputDir, displayName, sourceArchiveLabel, depth,
                archiveFingerprint, processNested, kindOf(archive.toString()), skipNestedEntries);
    }

    private int scanDiskArchive(Path archive, Path outputDir, String displayName,
                                String sourceArchiveLabel, int depth, String archiveFingerprint,
                                boolean processNested, ArchiveKind kind,
                                Set<String> skipNestedEntries)
            throws IOException, InterruptedException {
        final ZipFile zip;
        try {
            zip = new ZipFile(archive.toFile());
        } catch (IOException openEx) {
            warnSkipArchive(sourceArchiveLabel, displayName, openEx);
            return 0;
        }
        int added = 0;
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkInterrupted();
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = ArchiveNames.requireSafeJarEntryName(entry.getName(), displayName);
                if (processNested && options.processNestedArchives && ArchiveNames.isNestedArchive(entryName)) {
                    if (!skipNestedEntries.contains(entryName)) {
                        handleNested(zip, entry, entryName, outputDir, kind, sourceArchiveLabel,
                                archiveFingerprint, depth);
                    }
                    continue;
                }
                if (!matchesArchiveClass(kind, entryName)) {
                    continue;
                }
                addZipClassTask(kind, zip, entry, entryName, archive, outputDir, displayName,
                        sourceArchiveLabel, archiveFingerprint);
                added++;
            }
        } finally {
            zip.close();
        }
        return added;
    }

    private void handleNested(ZipFile outer, ZipEntry entry, String entryName, Path parentOutputDir,
                              ArchiveKind parentKind, String sourceArchiveLabel,
                              String parentFingerprint, int parentDepth)
            throws IOException, InterruptedException {
        int depth = parentDepth + 1;
        if (depth > ArchiveLimits.MAX_NESTED_DEPTH) {
            throw new IOException("Nested archive depth exceeds " + ArchiveLimits.MAX_NESTED_DEPTH
                    + " in " + sourceArchiveLabel + "!" + entry.getName());
        }
        recordNestedArchive(sourceArchiveLabel + "!" + entry.getName());

        ArchiveKind nestedKind = kindOf(entryName);
        String nestedSource = sourceArchiveLabel + "!" + entryName;
        String nestedFingerprintValue = nestedFingerprint(parentFingerprint, entry);
        Path nestedOutputBase = nestedOutputBase(parentKind, parentOutputDir, entryName);
        String nestedDisplayName = nestedDisplayName(parentKind, entryName);
        Path nestedArchiveOutputDir = nestedOutputBase.resolve(ArchiveNames.stripExtension(nestedDisplayName));

        List<NestedPreviewCopy> previewCopies = new ArrayList<NestedPreviewCopy>();
        NestedPreview preview;
        try {
            preview = previewNested(outer, entry, nestedKind, nestedSource, nestedFingerprintValue,
                    nestedArchiveOutputDir, depth, previewCopies);
        } catch (IOException previewError) {
            revertPreviewCopyCounts(previewCopies);
            deletePreviewCopies(previewCopies);
            fallbackExtractNested(outer, entry, nestedOutputBase, nestedDisplayName, nestedSource,
                    sourceArchiveLabel, depth, nestedFingerprintValue, previewError);
            return;
        }
        if (!preview.sawFileEntry) {
            revertPreviewCopyCounts(previewCopies);
            deletePreviewCopies(previewCopies);
            fallbackExtractNested(outer, entry, nestedOutputBase, nestedDisplayName, nestedSource,
                    sourceArchiveLabel, depth, nestedFingerprintValue, null);
            return;
        }

        Set<String> previewedNested = new HashSet<String>();
        for (NestedPreviewCopy copy : previewCopies) {
            previewedNested.add(copy.entryName);
            int added = processArchive(copy.path, copy.outputBase, copy.displayName, copy.sourceLabel,
                    copy.depth, copy.fingerprint, true);
            if (added == 0) {
                deleteExtractedArchive(copy.path);
            }
        }
        if (preview.hasMatch) {
            Path extracted = extractNested(outer, entry, depth, sourceArchiveLabel);
            processArchive(extracted, nestedOutputBase, nestedDisplayName, nestedSource, depth,
                    nestedFingerprintValue, true, previewedNested);
        }
    }

    private NestedPreview previewNested(ZipFile outer, ZipEntry entry, ArchiveKind nestedKind,
                                        String nestedSource, String nestedFingerprint,
                                        Path nestedArchiveOutputDir, int depth,
                                        List<NestedPreviewCopy> previewCopies)
            throws IOException, InterruptedException {
        NestedPreview preview = new NestedPreview();
        try (InputStream raw = outer.getInputStream(entry);
             ZipInputStream zis = new ZipInputStream(raw)) {
            ZipEntry child;
            while ((child = zis.getNextEntry()) != null) {
                checkInterrupted();
                if (child.isDirectory()) {
                    continue;
                }
                preview.sawFileEntry = true;
                String childName = ArchiveNames.requireSafeJarEntryName(child.getName(), nestedSource);
                if (options.processNestedArchives && ArchiveNames.isNestedArchive(childName)) {
                    Path copy = writeNestedArchive(zis, child.getName(), child.getSize(), depth + 1,
                            nestedSource + "!" + childName);
                    String childSource = nestedSource + "!" + childName;
                    try {
                        recordNestedArchive(childSource);
                    } catch (IOException e) {
                        deleteExtractedArchive(copy);
                        throw e;
                    }
                    previewCopies.add(new NestedPreviewCopy(copy, childName,
                            nestedOutputBase(nestedKind, nestedArchiveOutputDir, childName),
                            nestedDisplayName(nestedKind, childName),
                            childSource,
                            nestedFingerprint(nestedFingerprint, child),
                            depth + 1));
                    continue;
                }
                if (matchesArchiveClass(nestedKind, childName)) {
                    preview.hasMatch = true;
                }
            }
        }
        return preview;
    }

    private void fallbackExtractNested(ZipFile outer, ZipEntry entry, Path nestedOutputBase,
                                       String nestedDisplayName, String nestedSource,
                                       String sourceArchiveLabel, int depth,
                                       String nestedFingerprint, IOException previewError)
            throws IOException, InterruptedException {
        if (options.debug) {
            String reason = previewError == null
                    ? "stream preview found no file entries"
                    : previewError.getMessage();
            System.err.println("[debug] nested stream preview failed, extracting: "
                    + nestedSource + ": " + reason);
        }
        Path extracted = extractNested(outer, entry, depth, sourceArchiveLabel);
        int added = processArchive(extracted, nestedOutputBase, nestedDisplayName, nestedSource, depth,
                nestedFingerprint, true);
        if (added == 0) {
            deleteExtractedArchive(extracted);
        }
    }

    private Path nestedOutputBase(ArchiveKind parentKind, Path parentOutputDir, String entryName) {
        if (parentKind == ArchiveKind.WAR
                && entryName.startsWith(ArchiveNames.WEB_LIB)
                && entryName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return parentOutputDir.resolve("WEB-INF").resolve("lib");
        }
        return parentOutputDir.resolve("nested");
    }

    private String nestedDisplayName(ArchiveKind parentKind, String entryName) {
        if (parentKind == ArchiveKind.WAR
                && entryName.startsWith(ArchiveNames.WEB_LIB)
                && entryName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return ArchiveNames.safeFileName(entryName.substring(ArchiveNames.WEB_LIB.length()));
        }
        return ArchiveNames.safeArchiveOutputName(entryName);
    }

    private boolean matchesArchiveClass(ArchiveKind kind, String entryName) {
        String mapped = mappedClassEntry(kind, entryName);
        return mapped != null && matcher.matchesClassEntry(mapped);
    }

    private String mappedClassEntry(ArchiveKind kind, String entryName) {
        if (kind == ArchiveKind.WAR) {
            if (!entryName.startsWith(ArchiveNames.WEB_CLASSES)) {
                return null;
            }
            return entryName.substring(ArchiveNames.WEB_CLASSES.length());
        }
        return ArchiveNames.mapJarClassEntry(entryName);
    }

    private void addZipClassTask(ArchiveKind kind, ZipFile zip, ZipEntry entry, String entryName,
                                 Path archive, Path outputDir, String displayName,
                                 String sourceArchiveLabel, String archiveFingerprint)
            throws IOException {
        if (kind == ArchiveKind.WAR) {
            String mapped = entryName.substring(ArchiveNames.WEB_CLASSES.length());
            addTask(new DecompileTask("WEB-INF/classes!" + mapped,
                    outputDir.resolve("WEB-INF").resolve("classes"), mapped,
                    sourceArchiveLabel + "!" + DecompileUtils.toClassName(mapped),
                    new ZipInputSource(archive, entryName, entry.getCrc(), entry.getSize(),
                            archiveFingerprint),
                    readOuterEntry(zip, entry, mapped, sourceArchiveLabel)));
            return;
        }
        String mapped = ArchiveNames.mapJarClassEntry(entryName);
        addTask(new DecompileTask(displayName + "!" + mapped, outputDir, mapped,
                sourceArchiveLabel + "!" + DecompileUtils.toClassName(mapped),
                new ZipInputSource(archive, entryName, entry.getCrc(), entry.getSize(),
                        archiveFingerprint),
                readOuterEntry(zip, entry, mapped, displayName)));
    }

    private void addTask(DecompileTask task) throws IOException {
        long count = matchedTargetClasses.incrementAndGet();
        if (count > ArchiveLimits.MAX_TARGET_CLASSES) {
            throw new IOException("Target class count exceeds " + ArchiveLimits.MAX_TARGET_CLASSES
                    + " while scanning " + task.sourceLocation);
        }
        collected.add(task);
    }

    private synchronized void warnSkipArchive(String sourceArchiveLabel, String displayName,
                                              IOException error) {
        System.err.println("[warn] Skipping unreadable archive: " + sourceArchiveLabel
                + " (" + displayName + "): " + error.getMessage());
        if (options.debug) {
            error.printStackTrace(System.err);
        }
    }

    private Path extractNested(ZipFile zip, ZipEntry entry, int depth, String source) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return writeNestedArchive(in, entry.getName(), entry.getSize(), depth,
                    source + "!" + entry.getName());
        }
    }

    private Path writeNestedArchive(InputStream in, String entryName, long size, int depth, String source)
            throws IOException {
        if (depth > ArchiveLimits.MAX_NESTED_DEPTH) {
            throw new IOException("Nested archive depth exceeds " + ArchiveLimits.MAX_NESTED_DEPTH
                    + " in " + source);
        }
        synchronized (extractLock) {
            long remainingBudget = ArchiveLimits.MAX_NESTED_EXTRACTED_BYTES - nestedExtractedBytes.get();
            if (size >= 0L && size > remainingBudget) {
                throw new IOException("Nested archive extraction budget exceeded in " + source);
            }
            Path target = tempRoot.resolve("nested")
                    .resolve(nestedSeq.incrementAndGet() + "-" + ArchiveNames.safeArchiveOutputName(entryName));
            Files.createDirectories(target.getParent());
            try {
                OutputStream out = Files.newOutputStream(target);
                long copied;
                try {
                    copied = ArchiveLimits.copyLimited(in, out, remainingBudget, source);
                } finally {
                    out.close();
                }
                nestedExtractedBytes.addAndGet(copied);
                return target;
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
    }

    private void recordNestedArchive(String source) throws IOException {
        int count = nestedArchiveCount.incrementAndGet();
        if (count > ArchiveLimits.MAX_NESTED_ARCHIVES) {
            nestedArchiveCount.decrementAndGet();
            throw new IOException("Nested archive count exceeds "
                    + ArchiveLimits.MAX_NESTED_ARCHIVES + " in " + source);
        }
    }

    private void revertPreviewCopyCounts(List<NestedPreviewCopy> copies) {
        for (int i = 0; i < copies.size(); i++) {
            nestedArchiveCount.decrementAndGet();
        }
    }

    private void deletePreviewCopies(List<NestedPreviewCopy> copies) {
        for (NestedPreviewCopy copy : copies) {
            deleteExtractedArchive(copy.path);
        }
        copies.clear();
    }

    private void deleteExtractedArchive(Path path) {
        if (path == null) {
            return;
        }
        synchronized (extractLock) {
            long size = 0L;
            try {
                if (Files.isRegularFile(path)) {
                    size = Files.size(path);
                }
            } catch (IOException ignored) {
            }
            boolean deleted;
            try {
                deleted = Files.deleteIfExists(path);
            } catch (IOException ignored) {
                return;
            }
            if (!deleted || size <= 0L) {
                return;
            }
            final long released = size;
            nestedExtractedBytes.updateAndGet(new LongUnaryOperator() {
                @Override
                public long applyAsLong(long current) {
                    return current <= released ? 0L : current - released;
                }
            });
        }
    }

    long extractedNestedBytes() {
        return nestedExtractedBytes.get();
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Archive scan interrupted");
        }
    }

    private String archiveFingerprint(Path archive) throws IOException {
        Path normalized = archive.toAbsolutePath().normalize();
        return normalized + "|" + Files.size(normalized) + "|"
                + Files.getLastModifiedTime(normalized).toMillis();
    }

    private String nestedFingerprint(String parentFingerprint, ZipEntry entry) {
        return parentFingerprint + "!" + entry.getName() + "|"
                + entry.getCrc() + "|" + entry.getSize();
    }

    private String directoryFingerprint(Path inputDir, List<Path> classFiles) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        updateDigest(digest, inputDir.toAbsolutePath().normalize().toString());
        for (Path classFile : classFiles) {
            updateDigest(digest, ArchiveNames.normalizeZipName(inputDir.relativize(classFile).toString()));
            updateDigest(digest, String.valueOf(Files.size(classFile)));
            updateDigest(digest, String.valueOf(Files.getLastModifiedTime(classFile).toMillis()));
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
        }
        return result.toString();
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private String readOuterEntry(Path classFile, String entryName) {
        if (entryName.indexOf('$') < 0) return null;
        try (InputStream in = Files.newInputStream(classFile)) {
            return ClassFileMetadata.read(in, classFile.toString()).outerEntryName;
        } catch (IOException e) {
            debugMetadataFailure(classFile.toString(), e);
            return null;
        }
    }

    private String readOuterEntry(ZipFile zip, ZipEntry entry, String mapped, String source) {
        if (mapped.indexOf('$') < 0) return null;
        if (entry.getSize() > ArchiveLimits.MAX_CLASS_BYTES) {
            debugMetadataFailure(source + "!" + entry.getName(),
                    new IOException("Class exceeds " + ArchiveLimits.MAX_CLASS_BYTES + " bytes"));
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return ClassFileMetadata.read(in, source + "!" + entry.getName()).outerEntryName;
        } catch (IOException e) {
            debugMetadataFailure(source + "!" + entry.getName(), e);
            return null;
        }
    }

    private synchronized void debugMetadataFailure(String source, IOException e) {
        if (options.debug) {
            System.err.println("[debug] failed to read inner-class metadata: " + source
                    + ": " + e.getMessage());
        }
    }

    private boolean isUnderOutput(Path path) {
        return path.toAbsolutePath().normalize().startsWith(options.output);
    }

    private static ArchiveKind kindOf(String name) {
        return ".war".equals(ArchiveNames.extension(name)) ? ArchiveKind.WAR : ArchiveKind.JAR;
    }

    private enum ArchiveKind {
        JAR,
        WAR
    }

    private static final class NestedPreview {
        boolean hasMatch;
        boolean sawFileEntry;
    }

    private static final class NestedPreviewCopy {
        final Path path;
        final String entryName;
        final Path outputBase;
        final String displayName;
        final String sourceLabel;
        final String fingerprint;
        final int depth;

        NestedPreviewCopy(Path path, String entryName, Path outputBase, String displayName,
                          String sourceLabel, String fingerprint, int depth) {
            this.path = path;
            this.entryName = entryName;
            this.outputBase = outputBase;
            this.displayName = displayName;
            this.sourceLabel = sourceLabel;
            this.fingerprint = fingerprint;
            this.depth = depth;
        }
    }
}
