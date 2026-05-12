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

    private CliOptions(Path input, Path output, List<String> packages, String outputEncoding,
                       boolean keepTemp, boolean debug, boolean help) {
        this.input = input;
        this.output = output;
        this.packages = packages;
        this.outputEncoding = outputEncoding;
        this.keepTemp = keepTemp;
        this.debug = debug;
        this.help = help;
    }

    public static CliOptions parse(String[] args) {
        if (args.length == 0 || has(args, "-h") || has(args, "--help")) {
            return new CliOptions(null, null, Collections.<String>emptyList(),
                    ArchiveNames.DEFAULT_OUTPUT_ENCODING, false, has(args, "--debug"), true);
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
        if (args.length < 3) {
            throw new UsageException("Missing arguments.");
        }

        Path input = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        boolean keepTemp = false;
        boolean debug = false;
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
            } else {
                packages.addAll(parsePackages(new String[] { arg }));
            }
        }
        return validate(new CliOptions(input, output, packages, outputEncoding, keepTemp, debug, false));
    }

    private static CliOptions parseNamed(String[] args) {
        Path input = null;
        Path output = null;
        boolean keepTemp = false;
        boolean debug = false;
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
                    packages.addAll(parsePackages(new String[] { nextValue(args, ++i, arg) }));
                    break;
                case "--output-encoding":
                case "--outputencoding":
                    outputEncoding = nextValue(args, ++i, arg);
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

        return validate(new CliOptions(input, output, packages, outputEncoding, keepTemp, debug, false));
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
        if (Files.isDirectory(options.input) && options.input.equals(options.output)) {
            throw new UsageException("Output directory must not be the same as the input directory.");
        }
        if (options.packages.isEmpty()) {
            throw new UsageException("At least one package is required.");
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
}
