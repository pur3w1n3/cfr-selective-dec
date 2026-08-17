package com.aq.cfrselect.cli;

import com.aq.cfrselect.archive.ArchiveNames;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CliOptions {
    public final Path input;
    public final Path output;
    public final List<String> packages;
    public final String outputEncoding;
    public final boolean keepTemp;
    public final boolean debug;
    public final boolean help;
    public final int threads;
    public final boolean processNestedArchives;

    private CliOptions(Path input, Path output, List<String> packages, String outputEncoding,
                       boolean keepTemp, boolean debug, boolean help, int threads,
                       boolean processNestedArchives) {
        this.input = input;
        this.output = output;
        this.packages = packages;
        this.outputEncoding = outputEncoding;
        this.keepTemp = keepTemp;
        this.debug = debug;
        this.help = help;
        this.threads = threads;
        this.processNestedArchives = processNestedArchives;
    }

    public static CliOptions parse(String[] args) {
        if (args.length == 0 || has(args, "-h") || has(args, "--help")) {
            return new CliOptions(null, null, Collections.<String>emptyList(),
                    ArchiveNames.DEFAULT_OUTPUT_ENCODING, false, has(args, "--debug"), true,
                    defaultThreads(), !has(args, "--no-nested"));
        }

        if (args[0].startsWith("-")) {
            return parseNamed(args);
        }
        return parsePositional(args);
    }

    public static boolean has(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static CliOptions parsePositional(String[] args) {
        if (args.length < 2) {
            throw new UsageException("Missing arguments.");
        }

        Path input = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        boolean keepTemp = false;
        boolean debug = false;
        int threads = defaultThreads();
        boolean processNestedArchives = true;
        String outputEncoding = ArchiveNames.DEFAULT_OUTPUT_ENCODING;
        List<String> packages = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if ("--keep-temp".equals(arg)) {
                keepTemp = true;
            } else if ("--debug".equals(arg)) {
                debug = true;
            } else if ("--output-encoding".equals(arg) || "--outputencoding".equals(arg)) {
                outputEncoding = nextValue(args, ++i, arg);
            } else if ("--threads".equals(arg)) {
                threads = parseThreads(nextValue(args, ++i, arg));
            } else if ("--no-nested".equals(arg)) {
                processNestedArchives = false;
            } else if (arg.startsWith("-")) {
                throw new UsageException("Unknown option: " + arg);
            } else {
                packages.addAll(parsePackages(arg));
            }
        }
        return validate(new CliOptions(input, output, packages, outputEncoding, keepTemp, debug,
                false, threads, processNestedArchives));
    }

    private static CliOptions parseNamed(String[] args) {
        Path input = null;
        Path output = null;
        boolean keepTemp = false;
        boolean debug = false;
        int threads = defaultThreads();
        boolean processNestedArchives = true;
        String outputEncoding = ArchiveNames.DEFAULT_OUTPUT_ENCODING;
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
                    packages.addAll(parsePackages(nextValue(args, ++i, arg)));
                    break;
                case "--output-encoding":
                case "--outputencoding":
                    outputEncoding = nextValue(args, ++i, arg);
                    break;
                case "--threads":
                    threads = parseThreads(nextValue(args, ++i, arg));
                    break;
                case "--no-nested":
                    processNestedArchives = false;
                    break;
                case "--keep-temp":
                    keepTemp = true;
                    break;
                case "--debug":
                    debug = true;
                    break;
                default:
                    throw new UsageException("Unknown option: " + arg);
            }
        }

        return validate(new CliOptions(input, output, packages, outputEncoding, keepTemp, debug,
                false, threads, processNestedArchives));
    }

    private static CliOptions validate(CliOptions options) {
        if (options.input == null) {
            throw new UsageException("Missing input.");
        }
        if (!Files.isRegularFile(options.input) && !Files.isDirectory(options.input)) {
            throw new UsageException("Input path not found: " + options.input);
        }
        if (Files.isRegularFile(options.input) && !ArchiveNames.isSupportedTopLevelArchive(options.input)) {
            throw new UsageException("Input must be .jar, .war, or a directory: " + options.input);
        }
        if (options.output == null) {
            throw new UsageException("Missing output directory.");
        }
        if (Files.isDirectory(options.input) && options.input.startsWith(options.output)) {
            throw new UsageException("Output directory must not be the input directory or its parent.");
        }
        try {
            Charset.forName(options.outputEncoding);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UsageException("Unsupported output encoding: " + options.outputEncoding);
        }
        return options;
    }

    private static String nextValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    private static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static int parseThreads(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new UsageException("--threads must be >= 1");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new UsageException("Invalid --threads value: " + value);
        }
    }

    private static List<String> parsePackages(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
