package com.aq.cfrselect.core;

import com.aq.cfrselect.archive.ArchiveNames;
import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.matching.PackageMatcher;
import com.aq.cfrselect.model.ClassFileMatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class SelectiveDecompilerTaskCollector {
    private final CliOptions options;
    private final PackageMatcher matcher;
    private final Path tempRoot;
    private final SelectiveDecompilerSummary summary;

    SelectiveDecompilerTaskCollector(CliOptions options, PackageMatcher matcher,
                                     Path tempRoot, SelectiveDecompilerSummary summary) {
        this.options = options;
        this.matcher = matcher;
        this.tempRoot = tempRoot;
        this.summary = summary;
    }

    List<DecompileTask> collect() throws IOException, InterruptedException {
        List<DecompileTask> tasks = new ArrayList<DecompileTask>();
        Path normalizedOutputRoot = options.output;
        if (Files.isDirectory(options.input)) {
            processDirectory(options.input, normalizedOutputRoot, tasks);
        } else {
            processArchive(options.input, normalizedOutputRoot, ArchiveNames.sourceName(options.input),
                    options.input.toAbsolutePath().normalize().toString(), tasks);
        }
        List<DecompileTask> uniqueTasks = deduplicateByOutputTarget(tasks);
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
        Collections.sort(tasks, new Comparator<DecompileTask>() {
            @Override
            public int compare(DecompileTask a, DecompileTask b) {
                return a.displayName.compareTo(b.displayName);
            }
        });

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

    private String outputTargetKey(DecompileTask task) {
        String javaEntry = task.entryName.substring(0, task.entryName.length() - ".class".length()) + ".java";
        return task.outputDir.resolve(javaEntry).toAbsolutePath().normalize().toString();
    }

    private void processDirectory(Path inputDir, Path outputDir, List<DecompileTask> tasks)
            throws IOException, InterruptedException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(inputDir)) {
            paths = walk
                    .filter(new java.util.function.Predicate<Path>() {
                        @Override
                        public boolean test(Path path) {
                            return Files.isRegularFile(path)
                                    && !isUnderOutput(path)
                                    && path.getFileName().toString().toLowerCase().endsWith(".class");
                        }
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }

        List<ClassFileMatch> classFiles = new ArrayList<ClassFileMatch>();
        for (Path path : paths) {
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
            tasks.add(new DecompileTask(displayName, outputDir, classFile.entryName,
                    classFile.path.toAbsolutePath().normalize().toString(),
                    new DirectoryInputSource(classFile.path)));
        }

        List<Path> archives;
        try (Stream<Path> walk = Files.walk(inputDir)) {
            archives = walk
                    .filter(new java.util.function.Predicate<Path>() {
                        @Override
                        public boolean test(Path path) {
                            return Files.isRegularFile(path)
                                    && !isUnderOutput(path)
                                    && ArchiveNames.isSupportedTopLevelArchive(path);
                        }
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }

        for (Path archive : archives) {
            String displayName = ArchiveNames.normalizeZipName(inputDir.relativize(archive).toString());
            processArchive(archive, outputDir, displayName,
                    archive.toAbsolutePath().normalize().toString(), tasks);
        }
    }

    private void processArchive(Path archive, Path outputBase, String displayName,
                                String sourceArchiveLabel, List<DecompileTask> tasks)
            throws IOException, InterruptedException {
        String ext = ArchiveNames.extension(archive.toString());
        if (".war".equals(ext)) {
            processWar(archive, outputBase, sourceArchiveLabel, tasks);
        } else if (".jar".equals(ext)) {
            processJar(archive, outputBase, displayName, sourceArchiveLabel, tasks);
        } else {
            throw new IOException("Unsupported input type: " + archive);
        }
    }

    private void processJar(Path jarFile, Path outputDir, String displayName,
                            String sourceArchiveLabel, List<DecompileTask> tasks)
            throws IOException, InterruptedException {
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = ArchiveNames.requireSafeJarEntryName(entry.getName(), displayName);
                if (entry.isDirectory()) {
                    continue;
                }
                if (ArchiveNames.isNestedArchive(entryName)) {
                    Path nested = extractNested(zip, entry);
                    processArchive(nested, outputDir, ArchiveNames.safeArchiveOutputName(entryName),
                            sourceArchiveLabel + "!" + entryName, tasks);
                    continue;
                }

                String mapped = ArchiveNames.mapJarClassEntry(entryName);
                if (!matcher.matchesClassEntry(mapped)) {
                    continue;
                }
                tasks.add(new DecompileTask(displayName + "!" + mapped, outputDir, mapped,
                        sourceArchiveLabel + "!" + toClassName(mapped),
                        new ZipInputSource(jarFile, entryName)));
            }
        }
    }

    private void processWar(Path warFile, Path outputDir, String sourceArchiveLabel, List<DecompileTask> tasks)
            throws IOException, InterruptedException {
        try (ZipFile zip = new ZipFile(warFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = ArchiveNames.requireSafeJarEntryName(entry.getName(), warFile.toString());
                if (entry.isDirectory()) {
                    continue;
                }

                if (name.startsWith(ArchiveNames.WEB_LIB) && name.toLowerCase().endsWith(".jar")) {
                    Path libJar = extractNested(zip, entry);
                    String libName = ArchiveNames.safeFileName(name.substring(ArchiveNames.WEB_LIB.length()));
                    processJar(libJar, outputDir, libName, sourceArchiveLabel + "!" + name, tasks);
                    continue;
                }

                if (ArchiveNames.isNestedArchive(name)) {
                    Path nested = extractNested(zip, entry);
                    processArchive(nested, outputDir, ArchiveNames.safeArchiveOutputName(name),
                            sourceArchiveLabel + "!" + name, tasks);
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
                        outputDir, mapped, sourceArchiveLabel + "!" + toClassName(mapped),
                        new ZipInputSource(warFile, name)));
            }
        }
    }

    private static String toClassName(String entryName) {
        String withoutSuffix = entryName.substring(0, entryName.length() - ".class".length());
        return withoutSuffix.replace('/', '.').replace('\\', '.');
    }

    private Path extractNested(ZipFile zip, ZipEntry entry) throws IOException {
        String fileName = ArchiveNames.safeArchiveOutputName(entry.getName());
        Path target = tempRoot.resolve("nested").resolve(System.nanoTime() + "-" + fileName);
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private boolean isUnderOutput(Path path) {
        return path.toAbsolutePath().normalize().startsWith(options.output);
    }
}
