package com.aq.cfrselect.core;

import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.io.IoUtils;
import com.aq.cfrselect.matching.PackageMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class SelectiveDecompiler {
    private final CliOptions options;
    private final PackageMatcher matcher;
    private final Path tempRoot;
    private final SelectiveDecompilerSummary summary;

    public SelectiveDecompiler(CliOptions options) throws IOException {
        this.options = options;
        this.matcher = new PackageMatcher(options.packages);
        this.tempRoot = Files.createTempDirectory("cfr-selective-");
        this.summary = new SelectiveDecompilerSummary();
    }

    public int run() throws IOException, InterruptedException {
        try {
            Files.createDirectories(options.output);
            SelectiveDecompilerTaskCollector collector =
                    new SelectiveDecompilerTaskCollector(options, matcher, tempRoot, summary);
            List<DecompileTask> tasks = collector.collect();

            System.out.println("Matched unique class files: " + tasks.size());
            if (summary.duplicateUnits.get() > 0) {
                System.out.println("Skipped duplicate class files: " + summary.duplicateUnits.get());
            }

            SelectiveDecompilerExecutor executor =
                    new SelectiveDecompilerExecutor(options, tempRoot, summary);
            executor.runQueues(tasks);

            summary.write(options.output.resolve("summary.txt"));
            writeManifest(tasks, options.output.resolve("manifest.txt"));

            if (summary.matchedClasses.get() > 0) {
                System.out.println("Finished: success=" + summary.decompiledUnits.get()
                        + " failed=" + summary.failedUnits.get()
                        + " total=" + summary.matchedClasses.get());
            } else {
                System.out.println("No class files found to decompile.");
            }
            return summary.failedUnits.get() == 0 ? 0 : 1;
        } finally {
            if (options.keepTemp) {
                System.out.println("Temporary files kept at: " + tempRoot);
            } else {
                IoUtils.deleteRecursively(tempRoot);
            }
        }
    }

    private void writeManifest(List<DecompileTask> tasks, Path manifestFile) throws IOException {
        List<DecompileTask> completedTasks = new ArrayList<DecompileTask>();
        for (DecompileTask task : tasks) {
            if (hasReusableOutput(task)) {
                completedTasks.add(task);
            }
        }
        Collections.sort(completedTasks, new Comparator<DecompileTask>() {
            @Override
            public int compare(DecompileTask a, DecompileTask b) {
                return a.className.compareTo(b.className);
            }
        });

        List<String> lines = new ArrayList<String>();
        for (DecompileTask task : completedTasks) {
            lines.add(task.className + " " + task.sourceLocation);
        }
        Files.write(manifestFile, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private boolean hasReusableOutput(DecompileTask task) {
        Path outputFile = task.outputDir.resolve(toJavaEntry(task.entryName));
        try {
            return Files.isRegularFile(outputFile) && Files.size(outputFile) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static String toJavaEntry(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length()) + ".java";
    }
}
