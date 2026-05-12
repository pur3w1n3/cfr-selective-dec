package com.aq.cfrselect;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Main {
    private static final String WEB_CLASSES = "WEB-INF/classes/";
    private static final String WEB_LIB = "WEB-INF/lib/";
    private static final String BOOT_CLASSES = "BOOT-INF/classes/";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                printUsage();
                return;
            }

            SelectiveDecompiler tool = new SelectiveDecompiler(options);
            int exitCode = tool.run();
            System.exit(exitCode);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar cfr-selective-dec-standalone.jar <input.jar|input.war> <output-dir> <package1[,package2]> [packageN...]");
        System.out.println("  java -jar cfr-selective-dec-standalone.jar --input app.war --output out --packages com.demo,org.example");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -i, --input       Input .jar or .war");
        System.out.println("  -o, --output      Output directory");
        System.out.println("  -p, --packages    Package prefixes, comma or semicolon separated");
        System.out.println("      --keep-temp   Keep temporary filtered jars for inspection");
        System.out.println("  -h, --help        Show this help");
    }

    private static final class SelectiveDecompiler {
        private final Options options;
        private final PackageMatcher matcher;
        private final Path tempRoot;
        private int decompiledUnits;
        private int failedUnits;

        private SelectiveDecompiler(Options options) throws IOException {
            this.options = options;
            this.matcher = new PackageMatcher(options.packages);
            this.tempRoot = Files.createTempDirectory("cfr-selective-");
        }

        private int run() throws IOException, InterruptedException {
            try {
                Files.createDirectories(options.output);
                processArchive(options.input, options.output, sourceName(options.input), 0);

                if (decompiledUnits == 0) {
                    System.out.println("No matched classes found for packages: " + String.join(", ", options.packages));
                }

                System.out.println("Decompiled units: " + decompiledUnits + ", failed units: " + failedUnits);
                return failedUnits == 0 ? 0 : 1;
            } finally {
                if (options.keepTemp) {
                    System.out.println("Temporary files kept at: " + tempRoot);
                } else {
                    deleteRecursively(tempRoot);
                }
            }
        }

        private void processArchive(Path archive, Path outputBase, String displayName, int depth)
                throws IOException, InterruptedException {
            String ext = extension(archive.toString());
            if (".war".equals(ext)) {
                processWar(archive, outputBase.resolve(stripExtension(displayName)), depth);
            } else if (".jar".equals(ext)) {
                processJar(archive, outputBase.resolve(stripExtension(displayName)), displayName, depth);
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
                        return mapJarClassEntry(name);
                    }
                });
                if (filtered.classCount > 0) {
                    runCfr(filtered.filteredJar, outputDir, displayName, filtered.classCount, depth);
                }

                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !isNestedArchive(entry.getName())) {
                        continue;
                    }

                    Path nested = extractNested(zip, entry, depth);
                    processArchive(nested, outputDir.resolve("nested"), safeArchiveOutputName(entry.getName()), depth + 1);
                }
            }
        }

        private void processWar(Path warFile, Path outputDir, int depth) throws IOException, InterruptedException {
            System.out.println(indent(depth) + "Scanning WAR: " + warFile.getFileName());

            try (ZipFile zip = new ZipFile(warFile.toFile())) {
                FilterResult webClasses = createFilteredJar(zip, warFile.getFileName() + ":WEB-INF/classes", new Function<String, String>() {
                    @Override
                    public String apply(String name) {
                        String normalized = normalizeZipName(name);
                        if (!normalized.startsWith(WEB_CLASSES)) {
                            return null;
                        }
                        return normalized.substring(WEB_CLASSES.length());
                    }
                });

                if (webClasses.classCount > 0) {
                    runCfr(webClasses.filteredJar, outputDir.resolve("WEB-INF").resolve("classes"),
                            "WEB-INF/classes", webClasses.classCount, depth);
                }

                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String name = normalizeZipName(entry.getName());
                    if (!name.startsWith(WEB_LIB) || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        continue;
                    }

                    Path libJar = extractNested(zip, entry, depth);
                    String libName = safeFileName(name.substring(WEB_LIB.length()));
                    Path libOutput = outputDir.resolve("WEB-INF").resolve("lib").resolve(stripExtension(libName));
                    processJar(libJar, libOutput, libName, depth + 1);
                }
            }
        }

        private FilterResult createFilteredJar(ZipFile zip, String sourceName, Function<String, String> entryMapper)
                throws IOException {
            Path filteredJar = tempRoot.resolve("filtered-" + safeArchiveOutputName(sourceName) + "-"
                    + System.nanoTime() + ".jar");
            Files.createDirectories(filteredJar.getParent());

            int count = 0;
            Set<String> added = new HashSet<>();
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(filteredJar))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String mapped = entryMapper.apply(entry.getName());
                    if (mapped == null) {
                        continue;
                    }

                    mapped = normalizeZipName(mapped);
                    if (!matcher.matchesClassEntry(mapped) || !added.add(mapped)) {
                        continue;
                    }

                    JarEntry jarEntry = new JarEntry(mapped);
                    out.putNextEntry(jarEntry);
                    try (InputStream in = zip.getInputStream(entry)) {
                        copy(in, out);
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

        private Path extractNested(ZipFile zip, ZipEntry entry, int depth) throws IOException {
            String fileName = safeArchiveOutputName(entry.getName());
            Path target = tempRoot.resolve("nested").resolve(depth + "-" + System.nanoTime() + "-" + fileName);
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target);
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
                    "--outputencoding", "UTF-8",
                    "--silent", "true",
                    "--outputdir", outputDir.toString()
            };

            try {
                org.benf.cfr.reader.Main.main(cfrArgs);
                decompiledUnits++;
            } catch (RuntimeException e) {
                failedUnits++;
                System.err.println("CFR failed for " + displayName + ": " + e.getMessage());
            }
        }
    }

    private static final class Options {
        private final Path input;
        private final Path output;
        private final List<String> packages;
        private final boolean keepTemp;
        private final boolean help;

        private Options(Path input, Path output, List<String> packages, boolean keepTemp, boolean help) {
            this.input = input;
            this.output = output;
            this.packages = packages;
            this.keepTemp = keepTemp;
            this.help = help;
        }

        private static Options parse(String[] args) {
            if (args.length == 0 || has(args, "-h") || has(args, "--help")) {
                return new Options(null, null, Collections.<String>emptyList(), false, true);
            }

            if (args[0].startsWith("-")) {
                return parseNamed(args);
            }
            return parsePositional(args);
        }

        private static Options parsePositional(String[] args) {
            if (args.length < 3) {
                throw new UsageException("Missing arguments.");
            }

            Path input = Paths.get(args[0]).toAbsolutePath().normalize();
            Path output = Paths.get(args[1]).toAbsolutePath().normalize();
            List<String> packages = parsePackages(Arrays.copyOfRange(args, 2, args.length));
            return validate(new Options(input, output, packages, false, false));
        }

        private static Options parseNamed(String[] args) {
            Path input = null;
            Path output = null;
            boolean keepTemp = false;
            List<String> packages = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-i":
                    case "--input":
                        input = Paths.get(nextValue(args, ++i, arg)).toAbsolutePath().normalize();
                        break;
                    case "-o":
                    case "--output":
                        output = Paths.get(nextValue(args, ++i, arg)).toAbsolutePath().normalize();
                        break;
                    case "-p":
                    case "--packages":
                        packages.addAll(parsePackages(new String[] { nextValue(args, ++i, arg) }));
                        break;
                    case "--keep-temp":
                        keepTemp = true;
                        break;
                    default:
                        throw new UsageException("Unknown option: " + arg);
                }
            }

            return validate(new Options(input, output, packages, keepTemp, false));
        }

        private static Options validate(Options options) {
            if (options.input == null) {
                throw new UsageException("Missing input.");
            }
            if (!Files.isRegularFile(options.input)) {
                throw new UsageException("Input file not found: " + options.input);
            }
            if (!isSupportedTopLevelArchive(options.input)) {
                throw new UsageException("Input must be .jar or .war: " + options.input);
            }
            if (options.output == null) {
                throw new UsageException("Missing output directory.");
            }
            if (options.packages.isEmpty()) {
                throw new UsageException("At least one package is required.");
            }
            return options;
        }

        private static boolean has(String[] args, String expected) {
            for (String arg : args) {
                if (expected.equals(arg)) {
                    return true;
                }
            }
            return false;
        }

        private static String nextValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new UsageException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static final class PackageMatcher {
        private final List<String> prefixes;

        private PackageMatcher(List<String> packages) {
            this.prefixes = packages.stream()
                    .map(PackageMatcher::normalizePackage)
                    .filter(new java.util.function.Predicate<String>() {
                        @Override
                        public boolean test(String s) {
                            return s != null && !s.trim().isEmpty();
                        }
                    })
                    .distinct()
                    .collect(Collectors.toList());
            if (this.prefixes.isEmpty()) {
                throw new UsageException("No valid packages specified.");
            }
        }

        private boolean matchesClassEntry(String entryName) {
            String normalized = normalizeZipName(entryName);
            if (!normalized.endsWith(".class")) {
                return false;
            }
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix + "/")) {
                    return true;
                }
            }
            return false;
        }

        private static String normalizePackage(String packageName) {
            String result = Objects.requireNonNull(packageName).trim();
            if (result.endsWith(".*")) {
                result = result.substring(0, result.length() - 2);
            }
            result = result.replace('.', '/').replace('\\', '/');
            while (result.startsWith("/")) {
                result = result.substring(1);
            }
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }
    }

    private static final class FilterResult {
        private final Path filteredJar;
        private final int classCount;

        private FilterResult(Path filteredJar, int classCount) {
            this.filteredJar = filteredJar;
            this.classCount = classCount;
        }
    }

    private static final class UsageException extends RuntimeException {
        private UsageException(String message) {
            super(message);
        }
    }

    private static List<String> parsePackages(String[] values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split("[,;]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static Path codeSourceDir() {
        try {
            Path location = Paths.get(Main.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    private static boolean isSupportedTopLevelArchive(Path path) {
        String ext = extension(path);
        return ".jar".equals(ext) || ".war".equals(ext);
    }

    private static boolean isNestedArchive(String name) {
        String lower = normalizeZipName(name).toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".war");
    }

    private static String extension(Path path) {
        return extension(path.getFileName().toString());
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripExtension(String name) {
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(0, index) : name;
    }

    private static String sourceName(Path path) {
        return path.getFileName().toString();
    }

    private static String normalizeZipName(String name) {
        return name.replace('\\', '/');
    }

    private static String mapJarClassEntry(String name) {
        String normalized = normalizeZipName(name);
        if (normalized.startsWith(BOOT_CLASSES)) {
            return normalized.substring(BOOT_CLASSES.length());
        }
        if (normalized.startsWith(WEB_CLASSES)) {
            return normalized.substring(WEB_CLASSES.length());
        }
        return normalized;
    }

    private static String safeFileName(String name) {
        String normalized = normalizeZipName(name);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String safeArchiveOutputName(String name) {
        return normalizeZipName(name)
                .replaceAll("^[A-Za-z]:", "")
                .replaceAll("[\\\\/:*?\"<>|]+", "_");
    }

    private static String indent(int depth) {
        StringBuilder result = new StringBuilder();
        for (int x = 0; x < Math.max(0, depth); x++) {
            result.append("  ");
        }
        return result.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(new Comparator<Path>() {
                @Override
                public int compare(Path a, Path b) {
                    return b.compareTo(a);
                }
            }).collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
