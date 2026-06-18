package com.aq.cfrselect.core;

import com.aq.cfrselect.archive.ArchiveNames;
import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.io.IoUtils;
import org.benf.cfr.reader.api.CfrDriver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class SelectiveDecompilerExecutor {
    static final int GROUP_SIZE = 128;

    private final CliOptions options;
    private final Path tempRoot;
    private final SelectiveDecompilerSummary summary;
    private final Object logLock = new Object();
    private final ConcurrentMap<Path, Object> outputLocks = new ConcurrentHashMap<Path, Object>();
    private final AtomicLong taskSequence = new AtomicLong();
    private List<Path> allArchives = new ArrayList<Path>();
    private ZipFilePool zipPool;
    /** Entry name index: maps normalized entry names to ZipInputSource for O(1) outer class lookup. */
    private Map<String, ZipInputSource> entryIndex;

    private enum TaskOutcome {
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

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

        private void succeed() {
            this.outcome = TaskOutcome.SUCCEEDED;
        }

        private void skip() {
            this.outcome = TaskOutcome.SKIPPED;
        }

        private void fail(String suffix) {
            this.outcome = TaskOutcome.FAILED;
            this.failureSuffix = suffix == null ? "" : suffix;
        }

        private void close() {
            if (outcome == null) {
                fail(" unexpected-exit");
            }
            switch (outcome) {
                case SUCCEEDED:
                    logSucceeded(taskName, startedAt);
                    break;
                case SKIPPED:
                    logSkipped(taskName);
                    break;
                case FAILED:
                default:
                    logFailed(taskName + failureSuffix, startedAt);
                    break;
            }
        }
    }

    private static final class BatchResult {
        final int completed;
        final int produced;
        final List<DecompileTask> remaining;

        BatchResult(int completed, int produced, List<DecompileTask> remaining) {
            this.completed = completed;
            this.produced = produced;
            this.remaining = remaining;
        }
    }

    SelectiveDecompilerExecutor(CliOptions options, Path tempRoot,
                                SelectiveDecompilerSummary summary) {
        this.options = options;
        this.tempRoot = tempRoot;
        this.summary = summary;
    }

    void runQueues(List<DecompileTask> tasks) throws IOException, InterruptedException {
        // Collect all unique jar archives across all tasks for cross-jar outer class lookup
        Set<Path> archives = new HashSet<Path>();
        for (DecompileTask task : tasks) {
            if (task.inputSource instanceof ZipInputSource) {
                archives.add(((ZipInputSource) task.inputSource).archive);
            }
        }
        allArchives = new ArrayList<Path>(archives);

        // Initialize the shared ZipFile pool to avoid repeated central directory reads
        zipPool = new ZipFilePool();
        // Build an entry-name index for O(1) outer class lookup across all archives
        entryIndex = buildEntryIndex(allArchives);

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        System.out.println("Queue executor: groupSize=" + GROUP_SIZE + " threads=" + threadCount
                + " total=" + tasks.size());

        List<DecompileTask> pending = new ArrayList<DecompileTask>();
        for (DecompileTask task : tasks) {
            if (markCached(task)) {
                continue;
            }
            pending.add(task);
        }

        int totalTasks = tasks.size();
        int completedTotal = totalTasks - pending.size();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            int round = 0;
            int batchSize = GROUP_SIZE;
            while (!pending.isEmpty()) {
                round++;
                List<List<DecompileTask>> groups = partition(pending, batchSize);
                summary.totalQueueTasks.addAndGet(groups.size());
                logInfo("[queue-round] round=" + round + " groups=" + groups.size()
                        + " pending=" + pending.size() + " groupSize=" + batchSize
                        + " progress=" + completedTotal + "/" + totalTasks
                        + " " + (totalTasks > 0 ? (completedTotal * 100 / totalTasks) : 0) + "%");

                List<Future<BatchResult>> futures = new ArrayList<Future<BatchResult>>(groups.size());
                for (List<DecompileTask> group : groups) {
                    futures.add(executor.submit(new GroupCallable(group)));
                }

                List<DecompileTask> nextPending = new ArrayList<DecompileTask>();
                int producedThisRound = 0;
                for (Future<BatchResult> future : futures) {
                    BatchResult result;
                    try {
                        result = future.get();
                    } catch (ExecutionException e) {
                        throw new IOException("Queue worker failed unexpectedly", e.getCause());
                    }
                    producedThisRound += result.produced;
                    nextPending.addAll(result.remaining);
                }

                completedTotal += producedThisRound;

                if (nextPending.isEmpty()) {
                    break;
                }
                if (producedThisRound == 0) {
                    if (batchSize == 1) {
                        markPermanentFailures(nextPending);
                        break;
                    }
                    batchSize = Math.max(1, batchSize / 2);
                    logInfo("[queue-retry-smaller] nextGroupSize=" + batchSize
                            + " pending=" + nextPending.size());
                    pending = nextPending;
                    continue;
                }
                pending = nextPending;
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
            // Release all pooled ZipFile resources
            zipPool.close();
        }
    }

    /**
     * Build a lookup index mapping normalized entry names to ZipInputSource entries.
     * This enables O(1) outer class resolution instead of scanning all archives.
     */
    private Map<String, ZipInputSource> buildEntryIndex(List<Path> archives) {
        Map<String, ZipInputSource> index = new HashMap<String, ZipInputSource>();
        for (Path archive : archives) {
            try (ZipFilePool.PooledZipFile pzf = zipPool.acquire(archive)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = pzf.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = ArchiveNames.normalizeZipName(entry.getName());
                    String mapped = ArchiveNames.mapJarClassEntry(name);
                    if (mapped.endsWith(".class")) {
                        index.putIfAbsent(mapped, new ZipInputSource(archive, name));
                    }
                }
            }
        }
        return index;
    }

    /** Open an InputStream from an InputSource, using the ZipFile pool when available. */
    private InputStream openInputSource(InputSource source) throws IOException {
        if (source instanceof ZipInputSource && zipPool != null) {
            return ((ZipInputSource) source).open(zipPool);
        }
        return source.open();
    }

    /**
     * Find an outer class InputSource using the entry index for O(1) lookup,
     * falling back to the primary source's sibling method.
     */
    private InputSource findOuterClass(InputSource primary, String outerEntry) {
        // First try the entry index for O(1) cross-archive lookup
        if (entryIndex != null) {
            ZipInputSource indexed = entryIndex.get(outerEntry);
            if (indexed != null) {
                return indexed;
            }
            // Also try common prefixes that may not be in the mapped index
            String[] prefixed = {"BOOT-INF/classes/" + outerEntry, "WEB-INF/classes/" + outerEntry};
            for (String candidate : prefixed) {
                indexed = entryIndex.get(candidate);
                if (indexed != null) {
                    return indexed;
                }
            }
        }
        // Fallback: try sibling in the same archive using pool
        if (primary instanceof ZipInputSource && zipPool != null) {
            return ((ZipInputSource) primary).sibling(outerEntry, zipPool);
        }
        return primary.sibling(outerEntry);
    }

    private BatchResult runGroup(List<DecompileTask> group) throws IOException {
        long taskId = nextTaskId();
        String taskName = "group#" + taskId + " size=" + group.size() + " first=" + group.get(0).entryName;
        TaskLogScope scope = new TaskLogScope(taskName);

        Path batchRoot = tempRoot.resolve("group").resolve("batch-" + taskId + "-" + System.nanoTime());
        Path outputRoot = batchRoot.resolve("output");
        List<DecompileTask> remaining = new ArrayList<DecompileTask>();
        int completed = 0;
        int produced = 0;
        boolean batchSuccess = false;
        try {
            Files.createDirectories(outputRoot);
            batchSuccess = runBatchInputs(group, outputRoot);
            commitAvailableOutputs(group, outputRoot);

            for (DecompileTask task : group) {
                if (hasReusableOutput(task)) {
                    summary.decompiledUnits.incrementAndGet();
                    completed++;
                    produced++;
                } else {
                    remaining.add(task);
                }
            }

            if (remaining.isEmpty()) {
                scope.succeed();
            } else if (produced > 0 || batchSuccess) {
                scope.fail(" partial remaining=" + remaining.size());
            } else {
                scope.fail(" no-output");
            }
            return new BatchResult(completed, produced, remaining);
        } finally {
            IoUtils.deleteRecursively(batchRoot);
            summary.completedUnits.addAndGet(completed);
            scope.close();
        }
    }

    private boolean runBatchInputs(List<DecompileTask> group, Path outputRoot) throws IOException {
        Map<String, String> optionsMap = new HashMap<String, String>();
        optionsMap.put("hideutf", "false");
        optionsMap.put("outputencoding", options.outputEncoding);
        optionsMap.put("silent", "true");
        optionsMap.put("outputdir", outputRoot.toString());

        Path inputRoot = outputRoot.getParent().resolve("input");
        Path jarFile = inputRoot.resolve("batch.jar");
        createBatchJar(group, jarFile);

        List<String> inputs = new ArrayList<String>();
        inputs.add(jarFile.toString());

        try {
            new CfrDriver.Builder().withOptions(optionsMap).build().analyse(inputs);
        } catch (Throwable t) {
            debug("group decompiler failed: first=" + group.get(0).entryName + " size=" + group.size(), t);
            return false;
        }
        return validateBatchOutputs(group, outputRoot);
    }

    private void createBatchJar(List<DecompileTask> group, Path jarFile) throws IOException {
        Files.createDirectories(jarFile.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarFile))) {
            Set<String> seenEntries = new HashSet<String>();
            for (DecompileTask task : group) {
                if (!seenEntries.add(task.entryName)) {
                    debug("skip duplicate batch entry: " + task.entryName);
                    continue;
                }
                out.putNextEntry(new JarEntry(task.entryName));
                try (InputStream in = openInputSource(task.inputSource)) {
                    copy(in, out);
                }
                out.closeEntry();

                for (String outerEntry : outerEntryNames(task.entryName)) {
                    if (!seenEntries.add(outerEntry)) continue;
                    InputSource outerSource = findOuterClass(task.inputSource, outerEntry);
                    if (outerSource == null) {
                        debug("outer class not found: " + outerEntry);
                        continue;
                    }
                    out.putNextEntry(new JarEntry(outerEntry));
                    try (InputStream in = openInputSource(outerSource)) {
                        copy(in, out);
                    }
                    out.closeEntry();
                }
            }
        }
    }

    private static List<String> outerEntryNames(String entryName) {
        List<String> result = new ArrayList<String>();
        String current = entryName;
        while (true) {
            int slash = current.lastIndexOf('/');
            String simple = slash >= 0 ? current.substring(slash + 1) : current;
            int lastDollar = simple.lastIndexOf('$');
            if (lastDollar < 0) break;
            String pkg = slash >= 0 ? current.substring(0, slash + 1) : "";
            current = pkg + simple.substring(0, lastDollar) + ".class";
            result.add(current);
        }
        return result;
    }

    private boolean validateBatchOutputs(List<DecompileTask> group, Path outputRoot) {
        for (DecompileTask task : group) {
            Path generated = outputRoot.resolve(toJavaEntry(task.entryName));
            if (!isReusableFile(generated)) {
                return false;
            }
        }
        return true;
    }

    private void commitAvailableOutputs(List<DecompileTask> group, Path outputRoot) throws IOException {
        for (DecompileTask task : group) {
            Path generated = outputRoot.resolve(toJavaEntry(task.entryName));
            if (!isReusableFile(generated)) {
                continue;
            }
            Path target = outputTarget(task);
            synchronized (outputLock(target)) {
                if (isReusableFile(target)) {
                    continue;
                }
                try {
                    Files.createDirectories(target.getParent());
                    Files.copy(generated, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    debug("failed to commit output, will retry if still pending: " + target, e);
                }
            }
        }
    }

    private boolean markCached(DecompileTask task) {
        if (!hasReusableOutput(task)) {
            return false;
        }
        long taskId = nextTaskId();
        TaskLogScope scope = new TaskLogScope("cache-hit#" + taskId + " " + task.entryName);
        summary.decompiledUnits.incrementAndGet();
        summary.completedUnits.incrementAndGet();
        scope.skip();
        scope.close();
        return true;
    }

    private void markPermanentFailures(List<DecompileTask> tasks) {
        for (DecompileTask task : tasks) {
            summary.failedUnits.incrementAndGet();
            summary.completedUnits.incrementAndGet();
            summary.failedClasses.add(task.displayName);
            logFailed("permanent-failure " + task.entryName, System.nanoTime());
        }
    }

    private boolean hasReusableOutput(DecompileTask task) {
        Path target = outputTarget(task);
        synchronized (outputLock(target)) {
            return isReusableFile(target);
        }
    }

    private Path outputTarget(DecompileTask task) {
        return task.outputDir.resolve(toJavaEntry(task.entryName)).toAbsolutePath().normalize();
    }

    private Object outputLock(Path target) {
        // Group locks by parent directory to reduce lock object count
        // while still preventing concurrent writes to the same directory
        Path lockKey = target.getParent();
        Object existing = outputLocks.get(lockKey);
        if (existing != null) {
            return existing;
        }
        Object created = new Object();
        Object previous = outputLocks.putIfAbsent(lockKey, created);
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

    private List<List<DecompileTask>> partition(List<DecompileTask> tasks, int batchSize) {
        List<List<DecompileTask>> groups = new ArrayList<List<DecompileTask>>();
        for (int i = 0; i < tasks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, tasks.size());
            groups.add(new ArrayList<DecompileTask>(tasks.subList(i, end)));
        }
        return groups;
    }

    private void logCreated(String taskName) {
        synchronized (logLock) {
            System.out.println("[task-created] " + taskName);
        }
    }

    private void logSucceeded(String taskName, long startedAt) {
        synchronized (logLock) {
            System.out.println("[task-succeeded] " + taskName
                    + " elapsed=" + formatElapsed(startedAt));
        }
    }

    private void logFailed(String taskName, long startedAt) {
        synchronized (logLock) {
            System.out.println("[task-failed] " + taskName + " elapsed=" + formatElapsed(startedAt));
        }
    }

    private void logSkipped(String taskName) {
        synchronized (logLock) {
            System.out.println("[task-skipped] " + taskName);
        }
    }

    private void logInfo(String message) {
        synchronized (logLock) {
            System.out.println(message);
        }
    }

    private String formatElapsed(long startedAt) {
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return millis + "ms";
    }

    private long nextTaskId() {
        return taskSequence.incrementAndGet();
    }

    private void debug(String message) {
        if (!options.debug) {
            return;
        }
        synchronized (logLock) {
            System.err.println("[debug] " + message);
        }
    }

    private void debug(String message, Throwable t) {
        if (!options.debug) {
            return;
        }
        synchronized (logLock) {
            System.err.println("[debug] " + message);
            t.printStackTrace(System.err);
        }
    }

    private static String toJavaEntry(String entryName) {
        return DecompileUtils.toJavaEntry(entryName);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        DecompileUtils.copyStream(in, out);
    }

    private final class GroupCallable implements Callable<BatchResult> {
        private final List<DecompileTask> group;

        private GroupCallable(List<DecompileTask> group) {
            this.group = group;
        }

        @Override
        public BatchResult call() throws Exception {
            return runGroup(group);
        }
    }
}
