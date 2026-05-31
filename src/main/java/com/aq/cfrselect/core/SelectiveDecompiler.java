package com.aq.cfrselect.core;

import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.io.IoUtils;
import com.aq.cfrselect.matching.PackageMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
