package com.aq.cfrselect.core;

import com.aq.cfrselect.archive.ArchiveNames;
import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.io.IoUtils;
import com.aq.cfrselect.matching.PackageMatcher;
import com.aq.cfrselect.model.ClassFileMatch;
import com.aq.cfrselect.model.FilterResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SelectiveDecompiler {
    private final CliOptions options;
    private final PackageMatcher matcher;
    private final Path tempRoot;
    private int decompiledUnits;
    private int failedUnits;

    public SelectiveDecompiler(CliOptions options) throws IOException {
        this.options = options;
        this.matcher = new PackageMatcher(options.packages);
        this.tempRoot = Files.createTempDirectory("cfr-selective-");
    }

    public int run() throws IOException, InterruptedException {
        try {
            Files.createDirectories(options.output);
            if (Files.isDirectory(options.input)) {
                processDirectory(options.input, options.output.resolve(ArchiveNames.sourceName(options.input)), 0);
            } else {
                processArchive(options.input, options.output, ArchiveNames.sourceName(options.input), 0);
            }

            if (decompiledUnits == 0) {
                System.out.println("No matched classes found for packages: " + String.join(", ", options.packages));
            }

            System.out.println("Decompiled units: " + decompiledUnits + ", failed units: " + failedUnits);
            return failedUnits == 0 ? 0 : 1;
        } finally {
            if (options.keepTemp) {
                System.out.println("Temporary files kept at: " + tempRoot);
            } else {
                IoUtils.deleteRecursively(tempRoot);
            }
        }
    }

    private void processDirectory(Path inputDir, Path outputDir, int depth)
            throws IOException, InterruptedException {
        System.out.println(indent(depth) + "Scanning directory: " + inputDir);
        processDirectoryClasses(inputDir, outputDir, depth + 1);

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
            processArchive(archive, outputDir, displayName, depth + 1);
        }
    }

    private void processDirectoryClasses(Path inputDir, Path outputDir, int depth)
            throws IOException, InterruptedException {
        List<ClassFileMatch> classFiles;
        try (Stream<Path> walk = Files.walk(inputDir)) {
            classFiles = walk
                    .filter(new java.util.function.Predicate<Path>() {
                        @Override
                        public boolean test(Path path) {
                            return Files.isRegularFile(path)
                                    && !isUnderOutput(path)
                                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class");
                        }
                    })
                    .map(new Function<Path, ClassFileMatch>() {
                        @Override
                        public ClassFileMatch apply(Path path) {
                            return matcher.matchClassFile(inputDir, path);
                        }
                    })
                    .filter(new java.util.function.Predicate<ClassFileMatch>() {
                        @Override
                        public boolean test(ClassFileMatch match) {
                            return match != null;
                        }
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }

        Map<String, List<ClassFileMatch>> byRoot = new LinkedHashMap<>();
        for (ClassFileMatch match : classFiles) {
            List<ClassFileMatch> matches = byRoot.get(match.rootName);
            if (matches == null) {
                matches = new ArrayList<>();
                byRoot.put(match.rootName, matches);
            }
            matches.add(match);
        }

        for (Map.Entry<String, List<ClassFileMatch>> entry : byRoot.entrySet()) {
            String rootName = entry.getKey();
            String displayName = rootName.isEmpty() ? "classes" : rootName;
            Path targetDir = rootName.isEmpty() ? outputDir : outputDir.resolve(rootName);
            FilterResult filtered = createFilteredJar(entry.getValue(), displayName);
            if (filtered.classCount > 0) {
                runCfr(filtered.filteredJar, targetDir, displayName, filtered.classCount, depth);
            }
        }
    }

    private void processArchive(Path archive, Path outputBase, String displayName, int depth)
            throws IOException, InterruptedException {
        String ext = ArchiveNames.extension(archive.toString());
        if (".war".equals(ext)) {
            processWar(archive, outputBase.resolve(ArchiveNames.stripExtension(displayName)), depth);
        } else if (".jar".equals(ext)) {
            processJar(archive, outputBase.resolve(ArchiveNames.stripExtension(displayName)), displayName, depth);
        } else {
            throw new IOException("Unsupported input type: " + archive);
        }
    }

    private void processJar(Path jarFile, Path outputDir, String displayName, int depth)
            throws IOException, InterruptedException {
        System.out.println(indent(depth) + "Scanning JAR: " + displayName);

        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            FilterResult filtered = createFilteredJar(zip, displayName, new Function<String, String>() {
                @Override
                public String apply(String name) {
                    return ArchiveNames.mapJarClassEntry(name);
                }
            });
            if (filtered.classCount > 0) {
                runCfr(filtered.filteredJar, outputDir, displayName, filtered.classCount, depth);
            }

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = ArchiveNames.requireSafeJarEntryName(entry.getName(), displayName);
                if (entry.isDirectory() || !ArchiveNames.isNestedArchive(entryName)) {
                    continue;
                }

                Path nested = extractNested(zip, entry, depth, displayName);
                processArchive(nested, outputDir.resolve("nested"), ArchiveNames.safeArchiveOutputName(entryName), depth + 1);
            }
        }
    }

    private void processWar(Path warFile, Path outputDir, int depth) throws IOException, InterruptedException {
        System.out.println(indent(depth) + "Scanning WAR: " + warFile.getFileName());

        try (ZipFile zip = new ZipFile(warFile.toFile())) {
            FilterResult webClasses = createFilteredJar(zip, warFile.getFileName() + ":WEB-INF/classes", new Function<String, String>() {
                @Override
                public String apply(String name) {
                    String normalized = ArchiveNames.normalizeZipName(name);
                    if (!normalized.startsWith(ArchiveNames.WEB_CLASSES)) {
                        return null;
                    }
                    return normalized.substring(ArchiveNames.WEB_CLASSES.length());
                }
            });

            if (webClasses.classCount > 0) {
                runCfr(webClasses.filteredJar, outputDir.resolve("WEB-INF").resolve("classes"),
                        "WEB-INF/classes", webClasses.classCount, depth);
            }

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = ArchiveNames.requireSafeJarEntryName(entry.getName(), warFile.toString());
                if (entry.isDirectory()) {
                    continue;
                }

                if (!name.startsWith(ArchiveNames.WEB_LIB) || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }

                Path libJar = extractNested(zip, entry, depth, warFile.toString());
                String libName = ArchiveNames.safeFileName(name.substring(ArchiveNames.WEB_LIB.length()));
                Path libOutput = outputDir.resolve("WEB-INF").resolve("lib").resolve(ArchiveNames.stripExtension(libName));
                processJar(libJar, libOutput, libName, depth + 1);
            }
        }
    }

    private FilterResult createFilteredJar(ZipFile zip, String sourceName, Function<String, String> entryMapper)
            throws IOException {
        Path filteredJar = tempRoot.resolve("filtered-" + ArchiveNames.safeArchiveOutputName(sourceName) + "-"
                + System.nanoTime() + ".jar");
        Files.createDirectories(filteredJar.getParent());

        int count = 0;
        Set<String> added = new HashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(filteredJar))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = ArchiveNames.requireSafeJarEntryName(entry.getName(), sourceName);
                if (entry.isDirectory()) {
                    continue;
                }

                String mapped = entryMapper.apply(entryName);
                if (mapped == null) {
                    continue;
                }

                mapped = ArchiveNames.requireSafeJarEntryName(mapped, sourceName);
                if (!matcher.matchesClassEntry(mapped) || !added.add(mapped)) {
                    continue;
                }

                JarEntry jarEntry = new JarEntry(mapped);
                out.putNextEntry(jarEntry);
                try (InputStream in = zip.getInputStream(entry)) {
                    IoUtils.copy(in, out);
                }
                out.closeEntry();
                count++;
            }
        }

        if (count == 0) {
            Files.deleteIfExists(filteredJar);
            return new FilterResult(null, 0);
        }
        return new FilterResult(filteredJar, count);
    }

    private FilterResult createFilteredJar(List<ClassFileMatch> classFiles, String sourceName)
            throws IOException {
        Path filteredJar = tempRoot.resolve("filtered-" + ArchiveNames.safeArchiveOutputName(sourceName) + "-"
                + System.nanoTime() + ".jar");
        Files.createDirectories(filteredJar.getParent());

        int count = 0;
        Set<String> added = new HashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(filteredJar))) {
            for (ClassFileMatch classFile : classFiles) {
                String entryName = ArchiveNames.requireSafeJarEntryName(classFile.entryName, sourceName);
                if (!added.add(entryName)) {
                    continue;
                }

                JarEntry jarEntry = new JarEntry(entryName);
                out.putNextEntry(jarEntry);
                try (InputStream in = Files.newInputStream(classFile.path)) {
                    IoUtils.copy(in, out);
                }
                out.closeEntry();
                count++;
            }
        }

        if (count == 0) {
            Files.deleteIfExists(filteredJar);
            return new FilterResult(null, 0);
        }
        return new FilterResult(filteredJar, count);
    }

    private Path extractNested(ZipFile zip, ZipEntry entry, int depth, String sourceName) throws IOException {
        String fileName = ArchiveNames.safeArchiveOutputName(entry.getName());
        Path target = tempRoot.resolve("nested").resolve(depth + "-" + System.nanoTime() + "-" + fileName);
        Files.createDirectories(target.getParent());
        try (InputStream in = zip.getInputStream(entry);
             java.io.OutputStream out = Files.newOutputStream(target)) {
            IoUtils.copy(in, out);
        }
        return target;
    }

    private void runCfr(Path filteredJar, Path outputDir, String displayName, int classCount, int depth)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDir);
        System.out.println(indent(depth) + "Decompiling " + classCount + " matched classes from " + displayName);

        String[] cfrArgs = new String[] {
                filteredJar.toString(),
                "--hideutf", "false",
                "--outputencoding", options.outputEncoding,
                "--silent", "true",
                "--outputdir", outputDir.toString()
        };

        try {
            org.benf.cfr.reader.Main.main(cfrArgs);
            decompiledUnits++;
        } catch (RuntimeException e) {
            failedUnits++;
            System.err.println("CFR failed for " + displayName + ": " + e.getMessage());
            if (options.debug) {
                e.printStackTrace(System.err);
            }
        }
    }

    private boolean isUnderOutput(Path path) {
        return path.toAbsolutePath().normalize().startsWith(options.output);
    }

    private static String indent(int depth) {
        StringBuilder result = new StringBuilder();
        for (int x = 0; x < Math.max(0, depth); x++) {
            result.append("  ");
        }
        return result.toString();
    }
}
