package com.aq.cfrselect.core;

import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.io.IoUtils;
import org.benf.cfr.reader.api.CfrDriver;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class SelectiveDecompilerExecutor {
    static final int GROUP_SIZE = 128;

    private final CliOptions options;
    private final Path tempRoot;
    private final SelectiveDecompilerSummary summary;
    private final OutputCache outputCache;
    private final Object logLock = new Object();
    private final ConcurrentMap<Path, Object> outputLocks = new ConcurrentHashMap<Path, Object>();
    private final AtomicLong taskSequence = new AtomicLong();

    private enum TaskOutcome { SUCCEEDED, FAILED, RETRY, SKIPPED }

    private final class TaskLogScope {
        private final String taskName;
        private final long startedAt;
        private TaskOutcome outcome;
        private String failureSuffix = "";

        private TaskLogScope(String taskName) {
            this.taskName = taskName;
            this.startedAt = System.nanoTime();
            logCreated(taskName);
        }

        private void succeed() { outcome = TaskOutcome.SUCCEEDED; }
        private void skip() { outcome = TaskOutcome.SKIPPED; }

        private void fail(String suffix) {
            outcome = TaskOutcome.FAILED;
            failureSuffix = suffix == null ? "" : suffix;
        }

        private void retry(String suffix) {
            outcome = TaskOutcome.RETRY;
            failureSuffix = suffix == null ? "" : suffix;
        }

        private void close() {
            if (outcome == null) fail(" unexpected-exit");
            switch (outcome) {
                case SUCCEEDED:
                    logSucceeded(taskName, startedAt);
                    break;
                case SKIPPED:
                    logSkipped(taskName);
                    break;
                case RETRY:
                    logRetry(taskName + failureSuffix, startedAt);
                    break;
                case FAILED:
                default:
                    logFailed(taskName + failureSuffix, startedAt);
                    break;
            }
        }
    }

    private static final class BatchResult {
        final int producedClasses;
        final List<DecompileUnit> remaining;
        final List<DecompileUnit> permanentFailures;

        BatchResult(int producedClasses, List<DecompileUnit> remaining,
                    List<DecompileUnit> permanentFailures) {
            this.producedClasses = producedClasses;
            this.remaining = remaining;
            this.permanentFailures = permanentFailures;
        }
    }

    private static final class WorkGroup {
        final List<DecompileUnit> units;
        final SourceWorkspace workspace;

        WorkGroup(List<DecompileUnit> units, SourceWorkspace workspace) {
            this.units = units;
            this.workspace = workspace;
        }
    }

    private static final class SubmittedGroup {
        final WorkGroup group;
        final Future<BatchResult> future;

        SubmittedGroup(WorkGroup group, Future<BatchResult> future) {
            this.group = group;
            this.future = future;
        }
    }

    SelectiveDecompilerExecutor(CliOptions options, Path tempRoot,
                                  SelectiveDecompilerSummary summary, OutputCache outputCache) {
        this.options = options;
        this.tempRoot = tempRoot;
        this.summary = summary;
        this.outputCache = outputCache;
    }

    void runQueues(List<DecompileTask> tasks) throws IOException, InterruptedException {
        Map<String, List<DecompileTask>> tasksBySource = groupTasksBySource(tasks);
        List<List<WorkGroup>> groupsBySource = new ArrayList<List<WorkGroup>>();
        int sourceSequence = 0;
        int totalSourceUnits = 0;
        int cachedClasses = 0;

        for (List<DecompileTask> sourceTasks : tasksBySource.values()) {
            List<DecompileUnit> units = DecompileUnit.group(sourceTasks);
            totalSourceUnits += units.size();
            List<DecompileUnit> pending = new ArrayList<DecompileUnit>();
            for (DecompileUnit unit : units) {
                if (markCached(unit)) cachedClasses += unit.classCount();
                else pending.add(unit);
            }
            if (pending.isEmpty()) continue;

            Path sourceRoot = tempRoot.resolve("class-cache").resolve("source-" + (++sourceSequence));
            // Pending units may depend on cached units from the same source, so prepare the full source classpath.
            SourceWorkspace workspace = new SourceWorkspace(sourceRoot, units);
            groupsBySource.add(partition(pending, GROUP_SIZE, workspace));
        }

        summary.sourceUnits.set(totalSourceUnits);
        int threadCount = Math.max(1, options.threads);
        System.out.println("Queue executor: groupSize=" + GROUP_SIZE + " threads=" + threadCount
                + " sources=" + tasksBySource.size() + " sourceUnits=" + totalSourceUnits
                + " classes=" + tasks.size());

        int totalClasses = tasks.size();
        int completedTotal = cachedClasses;
        List<WorkGroup> currentGroups = interleave(groupsBySource);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            int round = 0;
            while (!currentGroups.isEmpty()) {
                round++;
                summary.totalQueueTasks.addAndGet(currentGroups.size());
                logInfo("[queue-round] round=" + round + " groups=" + currentGroups.size()
                        + " pendingClasses=" + countClasses(currentGroups)
                        + " maxGroupSize=" + maxGroupSize(currentGroups)
                        + " progress=" + completedTotal + "/" + totalClasses
                        + " " + (totalClasses > 0 ? completedTotal * 100 / totalClasses : 0) + "%");

                List<SubmittedGroup> submitted = new ArrayList<SubmittedGroup>(currentGroups.size());
                for (WorkGroup group : currentGroups) {
                    submitted.add(new SubmittedGroup(group, executor.submit(new GroupCallable(group))));
                }

                List<WorkGroup> nextGroups = new ArrayList<WorkGroup>();
                List<DecompileUnit> singleFailures = new ArrayList<DecompileUnit>();
                for (SubmittedGroup item : submitted) {
                    BatchResult result = await(item.future);
                    completedTotal += result.producedClasses;
                    if (!result.permanentFailures.isEmpty()) {
                        completedTotal += markPermanentFailures(result.permanentFailures);
                    }
                    if (result.remaining.isEmpty()) continue;
                    if (item.group.units.size() == 1) {
                        singleFailures.addAll(result.remaining);
                    } else {
                        // Keep the proven direct-to-single retry strategy; no binary backoff.
                        nextGroups.addAll(partition(result.remaining, 1, item.group.workspace));
                    }
                }

                completedTotal += markReusableOutputs(singleFailures);
                List<DecompileUnit> permanentFailures = filterUnfinished(singleFailures);
                if (!permanentFailures.isEmpty()) {
                    completedTotal += markPermanentFailures(permanentFailures);
                }
                completedTotal += removeReusableOutputs(nextGroups);
                currentGroups = nextGroups;
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) executor.shutdownNow();
        }
    }

    private Map<String, List<DecompileTask>> groupTasksBySource(List<DecompileTask> tasks) {
        Map<String, List<DecompileTask>> result = new LinkedHashMap<String, List<DecompileTask>>();
        for (DecompileTask task : tasks) {
            String key = task.inputSource.sourceKey() + "\u0000"
                    + task.outputDir.toAbsolutePath().normalize();
            List<DecompileTask> sourceTasks = result.get(key);
            if (sourceTasks == null) {
                sourceTasks = new ArrayList<DecompileTask>();
                result.put(key, sourceTasks);
            }
            sourceTasks.add(task);
        }
        return result;
    }

    private List<WorkGroup> interleave(List<List<WorkGroup>> groupsBySource) {
        List<WorkGroup> result = new ArrayList<WorkGroup>();
        for (int index = 0; ; index++) {
            boolean added = false;
            for (List<WorkGroup> sourceGroups : groupsBySource) {
                if (index < sourceGroups.size()) {
                    result.add(sourceGroups.get(index));
                    added = true;
                }
            }
            if (!added) return result;
        }
    }

    private BatchResult await(Future<BatchResult> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Queue worker failed unexpectedly", cause);
        }
    }

    private BatchResult runGroup(WorkGroup workGroup) {
        List<DecompileUnit> group = workGroup.units;
        long taskId = nextTaskId();
        String taskName = "group#" + taskId + " units=" + group.size()
                + " classes=" + classCount(group) + " first=" + group.get(0).primary.entryName;
        TaskLogScope scope = new TaskLogScope(taskName);
        Path batchRoot = tempRoot.resolve("group").resolve("batch-" + taskId + "-" + System.nanoTime());
        Path outputRoot = batchRoot.resolve("output");
        int producedClasses = 0;
        List<DecompileUnit> permanentFailures = new ArrayList<DecompileUnit>();
        List<DecompileUnit> availableUnits = new ArrayList<DecompileUnit>();

        try {
            workGroup.workspace.prepare();
            for (DecompileUnit unit : group) {
                IOException failure = workGroup.workspace.preparationFailure(unit);
                if (failure == null) {
                    availableUnits.add(unit);
                } else {
                    permanentFailures.add(unit);
                    debug("source unit preparation failed: " + unit.sourceEntry, failure);
                }
            }
            if (availableUnits.isEmpty()) {
                scope.fail(" source-prepare-failed units=" + permanentFailures.size());
                return new BatchResult(0, new ArrayList<DecompileUnit>(), permanentFailures);
            }

            Files.createDirectories(outputRoot);
            boolean batchSuccess = runBatchInputs(availableUnits, workGroup.workspace, outputRoot);
            List<DecompileUnit> remaining = commitAvailableOutputs(availableUnits, outputRoot);
            producedClasses = classCount(availableUnits) - classCount(remaining);
            summary.decompiledUnits.addAndGet(producedClasses);

            if (remaining.isEmpty()) scope.succeed();
            else if (producedClasses > 0 || batchSuccess) {
                scope.retry(" partial remainingUnits=" + remaining.size());
            } else scope.retry(" no-output");
            return new BatchResult(producedClasses, remaining, permanentFailures);
        } catch (IOException e) {
            debug("group I/O failed: first=" + group.get(0).primary.entryName
                    + " units=" + group.size(), e);
            scope.retry(" io-failure");
            return new BatchResult(0, availableUnits.isEmpty()
                    ? new ArrayList<DecompileUnit>(group) : availableUnits, permanentFailures);
        } finally {
            deleteBatchRoot(batchRoot);
            summary.completedUnits.addAndGet(producedClasses);
            scope.close();
        }
    }

    private boolean runBatchInputs(List<DecompileUnit> group, SourceWorkspace workspace,
                                   Path outputRoot) {
        Map<String, String> optionsMap = new HashMap<String, String>();
        optionsMap.put("hideutf", "false");
        optionsMap.put("outputencoding", options.outputEncoding);
        optionsMap.put("silent", "true");
        optionsMap.put("outputdir", outputRoot.toString());
        optionsMap.put("extraclasspath", workspace.classPathRoot().toString());

        List<String> inputs = new ArrayList<String>(group.size());
        for (DecompileUnit unit : group) inputs.add(workspace.inputFor(unit).toString());

        try {
            new CfrDriver.Builder().withOptions(optionsMap).build().analyse(inputs);
            return true;
        } catch (StackOverflowError e) {
            debug("group decompiler stack overflow: first=" + group.get(0).primary.entryName
                    + " units=" + group.size(), e);
            return false;
        } catch (RuntimeException e) {
            debug("group decompiler failed: first=" + group.get(0).primary.entryName
                    + " units=" + group.size(), e);
            return false;
        }
    }

    private List<DecompileUnit> commitAvailableOutputs(List<DecompileUnit> group, Path outputRoot) {
        List<DecompileUnit> remaining = new ArrayList<DecompileUnit>();
        for (DecompileUnit unit : group) {
            Path generated = outputRoot.resolve(unit.sourceEntry);
            if (!isReusableFile(generated)) {
                remaining.add(unit);
                continue;
            }

            Path target = unit.outputTarget();
            boolean available;
            synchronized (outputLock(target)) {
                available = false;
                try {
                    copyAtomically(generated, target);
                    outputCache.record(unit);
                    available = true;
                } catch (IOException e) {
                    debug("failed to commit output, will retry if still pending: " + target, e);
                }
            }
            if (!available) remaining.add(unit);
        }
        return remaining;
    }

    private void copyAtomically(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling("." + target.getFileName()
                + ".tmp-" + nextTaskId() + "-" + System.nanoTime());
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                debug("failed to delete temporary output: " + temporary, e);
            }
        }
    }

    private void deleteBatchRoot(Path batchRoot) {
        try {
            IoUtils.deleteRecursively(batchRoot);
        } catch (IOException e) {
            debug("failed to delete batch directory: " + batchRoot, e);
        }
    }

    private boolean markCached(DecompileUnit unit) {
        if (!outputCache.tryReuse(unit)) return false;
        long taskId = nextTaskId();
        TaskLogScope scope = new TaskLogScope("cache-hit#" + taskId + " " + unit.sourceEntry);
        summary.decompiledUnits.addAndGet(unit.classCount());
        summary.completedUnits.addAndGet(unit.classCount());
        summary.cacheHits.addAndGet(unit.classCount());
        scope.skip();
        scope.close();
        return true;
    }

    private int markReusableOutputs(List<DecompileUnit> units) {
        int classes = 0;
        for (DecompileUnit unit : units) {
            if (hasReusableOutput(unit)) {
                int count = unit.classCount();
                summary.decompiledUnits.addAndGet(count);
                summary.completedUnits.addAndGet(count);
                classes += count;
            }
        }
        return classes;
    }

    private List<DecompileUnit> filterUnfinished(List<DecompileUnit> units) {
        List<DecompileUnit> unfinished = new ArrayList<DecompileUnit>();
        for (DecompileUnit unit : units) if (!hasReusableOutput(unit)) unfinished.add(unit);
        return unfinished;
    }

    private int removeReusableOutputs(List<WorkGroup> groups) {
        int classes = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            WorkGroup group = groups.get(i);
            for (int j = group.units.size() - 1; j >= 0; j--) {
                DecompileUnit unit = group.units.get(j);
                if (hasReusableOutput(unit)) {
                    int count = unit.classCount();
                    summary.decompiledUnits.addAndGet(count);
                    summary.completedUnits.addAndGet(count);
                    classes += count;
                    group.units.remove(j);
                }
            }
            if (group.units.isEmpty()) groups.remove(i);
        }
        return classes;
    }

    private int markPermanentFailures(List<DecompileUnit> units) {
        int classes = 0;
        for (DecompileUnit unit : units) {
            if (hasReusableOutput(unit)) {
                int count = unit.classCount();
                summary.decompiledUnits.addAndGet(count);
                summary.completedUnits.addAndGet(count);
                classes += count;
                continue;
            }
            int count = unit.classCount();
            summary.failedUnits.addAndGet(count);
            summary.completedUnits.addAndGet(count);
            classes += count;
            for (DecompileTask member : unit.members) summary.failedClasses.add(member.displayName);
            logFailed("permanent-failure " + unit.sourceEntry, System.nanoTime());
        }
        return classes;
    }

    private boolean hasReusableOutput(DecompileUnit unit) {
        return outputCache.isCompleted(unit.outputTarget());
    }

    private Object outputLock(Path target) {
        Object existing = outputLocks.get(target);
        if (existing != null) return existing;
        Object created = new Object();
        Object previous = outputLocks.putIfAbsent(target, created);
        return previous == null ? created : previous;
    }

    private boolean isReusableFile(Path outputFile) {
        try {
            return Files.isRegularFile(outputFile) && Files.size(outputFile) > 0;
        } catch (IOException e) {
            debug("failed to inspect output: " + outputFile, e);
            return false;
        }
    }

    private List<WorkGroup> partition(List<DecompileUnit> units, int batchSize,
                                      SourceWorkspace workspace) {
        List<WorkGroup> groups = new ArrayList<WorkGroup>();
        int balancedSize = balancedBatchSize(units.size(), batchSize);
        for (int i = 0; i < units.size(); i += balancedSize) {
            int end = Math.min(i + balancedSize, units.size());
            groups.add(new WorkGroup(new ArrayList<DecompileUnit>(units.subList(i, end)), workspace));
        }
        return groups;
    }

    static int balancedBatchSize(int itemCount, int maximumBatchSize) {
        if (itemCount <= 0) return Math.max(1, maximumBatchSize);
        if (maximumBatchSize < 1) throw new IllegalArgumentException("maximumBatchSize must be >= 1");
        long groupCount = (itemCount + (long) maximumBatchSize - 1L) / maximumBatchSize;
        return (int) ((itemCount + groupCount - 1L) / groupCount);
    }

    private int countClasses(List<WorkGroup> groups) {
        int count = 0;
        for (WorkGroup group : groups) count += classCount(group.units);
        return count;
    }

    private int classCount(List<DecompileUnit> units) {
        int count = 0;
        for (DecompileUnit unit : units) count += unit.classCount();
        return count;
    }

    private int maxGroupSize(List<WorkGroup> groups) {
        int max = 0;
        for (WorkGroup group : groups) max = Math.max(max, group.units.size());
        return max;
    }

    private void logCreated(String taskName) {
        if (!options.debug) return;
        synchronized (logLock) { System.out.println("[task-created] " + taskName); }
    }

    private void logSucceeded(String taskName, long startedAt) {
        if (!options.debug) return;
        synchronized (logLock) {
            System.out.println("[task-succeeded] " + taskName + " elapsed=" + formatElapsed(startedAt));
        }
    }

    private void logFailed(String taskName, long startedAt) {
        synchronized (logLock) {
            System.out.println("[task-failed] " + taskName + " elapsed=" + formatElapsed(startedAt));
        }
    }

    private void logRetry(String taskName, long startedAt) {
        if (!options.debug) return;
        synchronized (logLock) {
            System.out.println("[task-retry] " + taskName + " elapsed=" + formatElapsed(startedAt));
        }
    }

    private void logSkipped(String taskName) {
        if (!options.debug) return;
        synchronized (logLock) { System.out.println("[task-skipped] " + taskName); }
    }

    private void logInfo(String message) {
        synchronized (logLock) { System.out.println(message); }
    }

    private String formatElapsed(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) + "ms";
    }

    private long nextTaskId() { return taskSequence.incrementAndGet(); }

    private void debug(String message, Throwable t) {
        if (!options.debug) return;
        synchronized (logLock) {
            System.err.println("[debug] " + message);
            t.printStackTrace(System.err);
        }
    }

    private final class GroupCallable implements Callable<BatchResult> {
        private final WorkGroup group;

        private GroupCallable(WorkGroup group) { this.group = group; }

        @Override
        public BatchResult call() { return runGroup(group); }
    }
}
