package com.aq.cfrselect.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class SelectiveDecompilerSummary {
    final AtomicInteger matchedClasses = new AtomicInteger();
    final AtomicInteger decompiledUnits = new AtomicInteger();
    final AtomicInteger failedUnits = new AtomicInteger();
    final AtomicInteger completedUnits = new AtomicInteger();
    final AtomicInteger totalQueueTasks = new AtomicInteger();
    final AtomicInteger duplicateUnits = new AtomicInteger();
    final List<String> failedClasses = new CopyOnWriteArrayList<String>();
    final List<String> duplicateClasses = new CopyOnWriteArrayList<String>();

    void write(Path summaryFile) throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("group_size=" + SelectiveDecompilerExecutor.GROUP_SIZE);
        lines.add("queue_tasks=" + totalQueueTasks.get());
        lines.add("success=" + decompiledUnits.get());
        lines.add("failed=" + failedUnits.get());
        lines.add("completed=" + completedUnits.get());
        lines.add("total=" + matchedClasses.get());
        lines.add("duplicates_skipped=" + duplicateUnits.get());
        lines.add("");
        lines.add("failed_classes:");
        appendSorted(lines, failedClasses);
        lines.add("");
        lines.add("duplicate_classes:");
        appendSorted(lines, duplicateClasses);
        Files.write(summaryFile, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void appendSorted(List<String> lines, List<String> items) {
        if (items.isEmpty()) {
            lines.add("(none)");
            return;
        }
        List<String> sorted = new ArrayList<String>(items);
        Collections.sort(sorted);
        lines.addAll(sorted);
    }
}
