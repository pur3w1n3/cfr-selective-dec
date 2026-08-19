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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.util.concurrent.atomic.AtomicLong;

final class SelectiveDecompilerTaskCollector {
    private static final AtomicLong nestedSeq = new AtomicLong();
    private final CliOptions options;
    private final PackageMatcher matcher;
    private final Path tempRoot;
    private final SelectiveDecompilerSummary summary;
    private long scannedArchiveEntries;
    private int nestedArchiveCount;
    private long nestedExtractedBytes;

    SelectiveDecompilerTaskCollector(CliOptions options, PackageMatcher matcher,
                                     Path tempRoot, SelectiveDecompilerSummary summary) {
        this.options = options;
        this.matcher = matcher;
        this.tempRoot = tempRoot;
        this.summary = summary;
    }

    List<DecompileTask> collect() throws IOException, InterruptedException {
        List<DecompileTask> tasks = new ArrayList<DecompileTask>();
        if (Files.isDirectory(options.input)) {
            processDirectory(options.input, options.output.resolve(ArchiveNames.sourceName(options.input)), tasks);
        } else {
            processArchive(options.input, options.output, ArchiveNames.sourceName(options.input),
                    options.input.toAbsolutePath().normalize().toString(), tasks, 0,
                    archiveFingerprint(options.input));
        }
        List<DecompileTask> isolatedTasks = isolateOutputNamespaces(tasks);
        List<DecompileTask> uniqueTasks = deduplicateByOutputTarget(isolatedTasks);
        Collections.sort(uniqueTasks, new Comparator<DecompileTask>() {
            @Override
            public int compare(DecompileTask a, DecompileTask b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
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

    private void processDirectory(Path inputDir, Path outputDir, List<DecompileTask> tasks)
            throws IOException, InterruptedException {
        // Single walk: collect both .class files and supported archives in one pass
        List<Path> classFilesRaw = new ArrayList<Path>();
        List<Path> archives = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(inputDir)) {
            walk.forEach(new java.util.function.Consumer<Path>() {
                @Override
                public void accept(Path path) {
                    if (!Files.isRegularFile(path) || isUnderOutput(path)) {
                        return;
                    }
                    String name = path.getFileName().toString().toLowerCase();
                    if (name.endsWith(".class")) {
                        classFilesRaw.add(path);
                    } else if (ArchiveNames.isSupportedTopLevelArchive(path)) {
                        archives.add(path);
                    }
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
            String displayName = classFile.rootName.isEmpty()
                    ? "classes!" + classFile.entryName
                    : classFile.rootName + "!" + classFile.entryName;
            Path taskOutputDir = classFile.rootName.isEmpty() ? outputDir : outputDir.resolve(classFile.rootName);
            tasks.add(new DecompileTask(displayName, taskOutputDir, classFile.entryName,
                    classFile.path.toAbsolutePath().normalize().toString(),
                    new DirectoryInputSource(classFile.path, classFile.entryName, directoryFingerprint),
                    readOuterEntry(classFile.path, classFile.entryName)));
        }

        for (Path archive : archives) {
            String displayName = ArchiveNames.normalizeZipName(inputDir.relativize(archive).toString());
            processArchive(archive, outputDir, displayName,
                    archive.toAbsolutePath().normalize().toString(), tasks, 0,
                    archiveFingerprint(archive));
        }
    }

    private void processArchive(Path archive, Path outputBase, String displayName,
                                String sourceArchiveLabel, List<DecompileTask> tasks, int depth,
                                String archiveFingerprint)
            throws IOException, InterruptedException {
        String ext = ArchiveNames.extension(archive.toString());
        if (".war".equals(ext)) {
            processWar(archive, outputBase.resolve(ArchiveNames.stripExtension(displayName)),
                    sourceArchiveLabel, tasks, depth, archiveFingerprint);
        } else if (".jar".equals(ext)) {
            processJar(archive, outputBase.resolve(ArchiveNames.stripExtension(displayName)),
                    displayName, sourceArchiveLabel, tasks, depth, archiveFingerprint);
        } else {
            throw new IOException("Unsupported input type: " + archive);
        }
    }

    private void processJar(Path jarFile, Path outputDir, String displayName,
                            String sourceArchiveLabel, List<DecompileTask> tasks, int depth,
                            String archiveFingerprint)
            throws IOException, InterruptedException {
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                recordArchiveEntry(displayName);
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = ArchiveNames.requireSafeJarEntryName(entry.getName(), displayName);
                if (ArchiveNames.isNestedArchive(entryName)) {
                    if (!options.processNestedArchives) continue;
                    Path nested = extractNested(zip, entry, depth + 1, sourceArchiveLabel);
                    processArchive(nested, outputDir.resolve("nested"), ArchiveNames.safeArchiveOutputName(entryName),
                            sourceArchiveLabel + "!" + entryName, tasks, depth + 1,
                            nestedFingerprint(archiveFingerprint, entry));
                    continue;
                }

                String mapped = ArchiveNames.mapJarClassEntry(entryName);
                if (!matcher.matchesClassEntry(mapped)) {
                    continue;
                }
                tasks.add(new DecompileTask(displayName + "!" + mapped, outputDir, mapped,
                        sourceArchiveLabel + "!" + DecompileUtils.toClassName(mapped),
                        new ZipInputSource(jarFile, entryName, entry.getCrc(), entry.getSize(),
                                archiveFingerprint),
                        readOuterEntry(zip, entry, mapped, displayName)));
            }
        }
    }

    private void processWar(Path warFile, Path outputDir, String sourceArchiveLabel,
                            List<DecompileTask> tasks, int depth, String archiveFingerprint)
            throws IOException, InterruptedException {
        try (ZipFile zip = new ZipFile(warFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                recordArchiveEntry(warFile.toString());
                String name = ArchiveNames.requireSafeJarEntryName(entry.getName(), warFile.toString());
                if (entry.isDirectory()) {
                    continue;
                }

                if (name.startsWith(ArchiveNames.WEB_LIB) && name.toLowerCase().endsWith(".jar")) {
                    if (!options.processNestedArchives) continue;
                    Path libJar = extractNested(zip, entry, depth + 1, sourceArchiveLabel);
                    String libName = ArchiveNames.safeFileName(name.substring(ArchiveNames.WEB_LIB.length()));
                    Path libOutput = outputDir.resolve("WEB-INF").resolve("lib")
                            .resolve(ArchiveNames.stripExtension(libName));
                    processJar(libJar, libOutput, libName, sourceArchiveLabel + "!" + name,
                            tasks, depth + 1, nestedFingerprint(archiveFingerprint, entry));
                    continue;
                }

                if (ArchiveNames.isNestedArchive(name)) {
                    if (!options.processNestedArchives) continue;
                    Path nested = extractNested(zip, entry, depth + 1, sourceArchiveLabel);
                    processArchive(nested, outputDir.resolve("nested"), ArchiveNames.safeArchiveOutputName(name),
                            sourceArchiveLabel + "!" + name, tasks, depth + 1,
                            nestedFingerprint(archiveFingerprint, entry));
                    continue;
                }

                String normalized = ArchiveNames.normalizeZipName(name);
                if (!normalized.startsWith(ArchiveNames.WEB_CLASSES)) {
                    continue;
                }

                String mapped = normalized.substring(ArchiveNames.WEB_CLASSES.length());
                if (!matcher.matchesClassEntry(mapped)) {
                    continue;
                }

                tasks.add(new DecompileTask("WEB-INF/classes!" + mapped,
                        outputDir.resolve("WEB-INF").resolve("classes"), mapped,
                        sourceArchiveLabel + "!" + DecompileUtils.toClassName(mapped),
                        new ZipInputSource(warFile, name, entry.getCrc(), entry.getSize(),
                                archiveFingerprint),
                        readOuterEntry(zip, entry, mapped, warFile.toString())));
            }
        }
    }

    private Path extractNested(ZipFile zip, ZipEntry entry, int depth, String source) throws IOException {
        if (depth > ArchiveLimits.MAX_NESTED_DEPTH) {
            throw new IOException("Nested archive depth exceeds " + ArchiveLimits.MAX_NESTED_DEPTH
                    + " in " + source + "!" + entry.getName());
        }
        if (++nestedArchiveCount > ArchiveLimits.MAX_NESTED_ARCHIVES) {
            throw new IOException("Nested archive count exceeds "
                    + ArchiveLimits.MAX_NESTED_ARCHIVES + " in " + source);
        }
        long remainingBudget = ArchiveLimits.MAX_NESTED_EXTRACTED_BYTES - nestedExtractedBytes;
        if (entry.getSize() >= 0L && entry.getSize() > remainingBudget) {
            throw new IOException("Nested archive extraction budget exceeded in "
                    + source + "!" + entry.getName());
        }
        String fileName = ArchiveNames.safeArchiveOutputName(entry.getName());
        Path target = tempRoot.resolve("nested").resolve(nestedSeq.incrementAndGet() + "-" + fileName);
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(target)) {
            nestedExtractedBytes += ArchiveLimits.copyLimited(in, out, remainingBudget,
                    source + "!" + entry.getName());
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return target;
    }

    private void recordArchiveEntry(String source) throws IOException {
        scannedArchiveEntries++;
        if (scannedArchiveEntries > ArchiveLimits.MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Archive entry count exceeds "
                    + ArchiveLimits.MAX_ARCHIVE_ENTRIES + " while scanning " + source);
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

    private void debugMetadataFailure(String source, IOException e) {
        if (options.debug) {
            System.err.println("[debug] failed to read inner-class metadata: " + source
                    + ": " + e.getMessage());
        }
    }

    private boolean isUnderOutput(Path path) {
        return path.toAbsolutePath().normalize().startsWith(options.output);
    }
}
