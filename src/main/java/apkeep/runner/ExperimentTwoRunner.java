package apkeep.runner;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Batch APKeep implementation of MINT Experiment 2. */
public final class ExperimentTwoRunner {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(6);
    private static final Duration DEFAULT_GRACE = Duration.ofMinutes(1);
    private static final long DEFAULT_HEAP_LIMIT = 120L * 1024L * 1024L * 1024L;
    private static final long SAMPLE_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ExperimentTwoRunner() {
    }

    public static int run(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            validateHeapLimit(parsed.heapLimitBytes);
            List<DatasetSpec> datasets = parseDatasets(parsed.datasetsFile);
            Path root = parsed.outputRoot != null
                    ? parsed.outputRoot
                    : Paths.get(System.getProperty("user.dir"),
                            "experiment-results", "experiment-2");
            return execute(datasets, parsed, root);
        } catch (IllegalArgumentException exception) {
            System.err.println("error: " + exception.getMessage());
            printUsage();
            return 2;
        } catch (Throwable failure) {
            System.err.println("error: " + failure.getMessage());
            failure.printStackTrace(System.err);
            return 1;
        }
    }

    static int execute(List<DatasetSpec> datasets, Arguments arguments, Path outputRoot)
            throws IOException, InterruptedException {
        Files.createDirectories(outputRoot);
        String runId = UUID.randomUUID().toString();
        String directoryName = LocalDateTime.now().format(DIRECTORY_TIME)
                + "-" + runId.substring(0, 8);
        Path runDirectory = outputRoot.resolve(directoryName);
        Instant started = Instant.now();
        boolean failed = false;
        boolean publish = true;

        ExperimentTwoResultWriter writer = new ExperimentTwoResultWriter(
                runDirectory, arguments.heapLimitBytes);
        try {
            writer.writeRunProperties("RUNNING", started, null, runId, datasets,
                    arguments, null);
            System.out.println("APKeep Experiment 2: datasets=" + datasets.size()
                    + " warmup_runs=1 measurement_runs=1 timeout="
                    + arguments.trialTimeout + " heap_limit_bytes="
                    + arguments.heapLimitBytes + " results=" + runDirectory);
            for (DatasetSpec spec : datasets) {
                System.out.println("Experiment 2 dataset started: dataset=" + spec.name
                        + " directory=" + spec.directory);
                DatasetInput input;
                try {
                    input = DatasetInput.loadForExperimentTwo(spec.directory, spec.name);
                } catch (Throwable loadFailure) {
                    failed = true;
                    writer.writePreparationFailure(spec, loadFailure);
                    writer.writeSummary();
                    System.err.println("Dataset preparation failed: dataset=" + spec.name
                            + " error=" + message(loadFailure));
                    continue;
                }
                if (input.ignoredNatUpdates > 0) {
                    System.err.println("WARN: ignoring nat_updates for dataset=" + spec.name
                            + " count=" + input.ignoredNatUpdates);
                }
                TrialResult.Counts counts = TrialResult.Counts.from(input.updates);
                TrialOutcome warmup;
                try {
                    warmup = runMonitored(input, counts, 0, "WARMUP", arguments);
                } catch (UnresponsiveTrialException fatal) {
                    writer.writeOutcome(input, counts, fatal.outcome);
                    failed = true;
                    publish = false;
                    throw fatal;
                }
                if (!warmup.successful()) {
                    failed = true;
                    writer.writeOutcome(input, counts, warmup);
                    if (warmup.abort()) writer.writeSkipped(input, counts, 1, warmup);
                    writer.writeSummary();
                    System.err.println("Warmup did not complete: dataset=" + spec.name
                            + " status=" + warmup.status + " error=" + warmup.error);
                    continue;
                }

                TrialOutcome measurement;
                try {
                    measurement = runMonitored(input, counts, 1, "MEASUREMENT", arguments);
                } catch (UnresponsiveTrialException fatal) {
                    writer.writeOutcome(input, counts, fatal.outcome);
                    failed = true;
                    publish = false;
                    throw fatal;
                }
                writer.writeOutcome(input, counts, measurement);
                writer.writeSummary();
                if (!measurement.successful()) failed = true;
                System.out.println("Experiment 2 dataset completed: dataset=" + spec.name
                        + " status=" + measurement.status
                        + (measurement.result == null ? "" : " model_ms="
                        + measurement.result.modelNanos / 1_000_000.0
                        + " verification_ms="
                        + measurement.result.verificationNanos / 1_000_000.0));
            }
            writer.writeSummary();
            writer.writeRunProperties(failed ? "COMPLETED_WITH_FAILURES" : "COMPLETED",
                    started, Instant.now(), runId, datasets, arguments, null);
            if (publish) writer.publishLatest(outputRoot);
        } catch (UnresponsiveTrialException fatal) {
            writer.writeSummary();
            writer.writeRunProperties("FAILED", started, Instant.now(), runId, datasets,
                    arguments, fatal.getMessage());
            System.err.println("Experiment worker did not stop within cancellation grace; "
                    + "restart the JVM before another experiment: " + fatal.getMessage());
            return 1;
        } finally {
            writer.close();
        }
        System.out.println("Experiment 2 results: " + runDirectory.toAbsolutePath());
        return failed ? 1 : 0;
    }

    private static TrialOutcome runMonitored(DatasetInput input, TrialResult.Counts counts,
            int trial, String phase, Arguments arguments) throws InterruptedException {
        final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "apkeep-experiment-2-trial");
                thread.setDaemon(false);
                return thread;
            }
        });
        Future<TrialResult> future = executor.submit(new java.util.concurrent.Callable<TrialResult>() {
            @Override
            public TrialResult call() throws Exception {
                return StandaloneRunner.runTrial(
                        input, RunMode.INCREMENTAL_INVARIANTS, trial, counts);
            }
        });
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long started = System.nanoTime();
        long deadline = saturatedAdd(started, arguments.trialTimeout.toNanos());
        long peak = Math.max(0L, memory.getHeapMemoryUsage().getUsed());
        try {
            while (true) {
                long used = Math.max(0L, memory.getHeapMemoryUsage().getUsed());
                peak = Math.max(peak, used);
                if (used >= arguments.heapLimitBytes) {
                    TrialOutcome outcome = TrialOutcome.memoryLimit(trial, phase, peak,
                            "heap used " + used + " reached limit "
                                    + arguments.heapLimitBytes);
                    cancel(future, executor, arguments.cancellationGrace, outcome);
                    return outcome;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    TrialOutcome outcome = TrialOutcome.timeout(trial, phase, peak,
                            "trial exceeded timeout " + arguments.trialTimeout);
                    cancel(future, executor, arguments.cancellationGrace, outcome);
                    return outcome;
                }
                try {
                    TrialResult result = future.get(Math.min(SAMPLE_NANOS, remaining),
                            TimeUnit.NANOSECONDS);
                    peak = Math.max(peak, memory.getHeapMemoryUsage().getUsed());
                    executor.shutdown();
                    return TrialOutcome.success(result, peak);
                } catch (TimeoutException sampleAgain) {
                    // Sample heap usage and deadline again.
                } catch (ExecutionException failure) {
                    executor.shutdown();
                    Throwable cause = failure.getCause();
                    if (cause instanceof OutOfMemoryError) {
                        return TrialOutcome.memoryLimit(trial, phase, peak,
                                "trial threw OutOfMemoryError: " + message(cause));
                    }
                    return TrialOutcome.failed(trial, phase, peak, cause);
                } catch (CancellationException cancelled) {
                    executor.shutdownNow();
                    return TrialOutcome.failed(trial, phase, peak, cancelled);
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (!executor.isShutdown()) executor.shutdownNow();
        }
    }

    private static void cancel(Future<?> future, ExecutorService executor, Duration grace,
            TrialOutcome outcome) throws InterruptedException {
        future.cancel(true);
        executor.shutdownNow();
        if (!executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new UnresponsiveTrialException(outcome,
                    "trial did not stop within " + grace);
        }
    }

    static List<DatasetSpec> parseDatasets(Path script) throws IOException {
        Path absolute = script.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("datasets file is missing: " + absolute);
        }
        List<DatasetSpec> result = new ArrayList<DatasetSpec>();
        Set<Path> directories = new HashSet<Path>();
        Set<String> names = new HashSet<String>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(absolute, StandardCharsets.UTF_8)) {
            lineNumber++;
            List<String> tokens = shellWords(raw, absolute, lineNumber);
            if (tokens.isEmpty()) continue;
            int mode = tokens.indexOf("-incr");
            if (mode < 0 || mode + 1 >= tokens.size()) {
                throw new IllegalArgumentException(absolute + ":" + lineNumber
                        + ": expected a command containing '-incr <dataset-directory>'");
            }
            if (tokens.subList(mode + 1, tokens.size()).contains("-incr")) {
                throw new IllegalArgumentException(absolute + ":" + lineNumber
                        + ": multiple -incr options");
            }
            String value = tokens.get(mode + 1);
            if (value.indexOf('$') >= 0 || value.indexOf('`') >= 0) {
                throw new IllegalArgumentException(absolute + ":" + lineNumber
                        + ": variable expansion and command substitution are not supported");
            }
            if (value.equals("~") || value.startsWith("~/")) {
                value = System.getProperty("user.home") + value.substring(1);
            }
            Path directory = Paths.get(value);
            if (!directory.isAbsolute()) directory = absolute.getParent().resolve(directory);
            directory = directory.normalize().toAbsolutePath();
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException(absolute + ":" + lineNumber
                        + ": dataset directory is missing: " + directory);
            }
            if (!directories.add(directory)) {
                throw new IllegalArgumentException("duplicate dataset directory: " + directory);
            }
            String name = logicalDatasetName(directory);
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate logical dataset name: " + name);
            }
            result.add(new DatasetSpec(name, directory));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("datasets file contains no -incr commands: "
                    + absolute);
        }
        Collections.sort(result, new Comparator<DatasetSpec>() {
            @Override
            public int compare(DatasetSpec left, DatasetSpec right) {
                return left.name.compareTo(right.name);
            }
        });
        return Collections.unmodifiableList(result);
    }

    private static String logicalDatasetName(Path directory) {
        Path parent = directory.getParent();
        if (parent != null && "experiment-inputs".equals(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent().getFileName().toString();
        }
        return directory.getFileName().toString();
    }

    private static List<String> shellWords(String line, Path file, int lineNumber) {
        List<String> result = new ArrayList<String>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        boolean active = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (escaped) {
                word.append(value);
                escaped = false;
                active = true;
            } else if (value == '\\' && quote != '\'') {
                escaped = true;
                active = true;
            } else if (quote != 0) {
                if (value == quote) quote = 0;
                else word.append(value);
                active = true;
            } else if (value == '\'' || value == '"') {
                quote = value;
                active = true;
            } else if (value == '#') {
                break;
            } else if (Character.isWhitespace(value)) {
                if (active) {
                    result.add(word.toString());
                    word.setLength(0);
                    active = false;
                }
            } else {
                word.append(value);
                active = true;
            }
        }
        if (escaped || quote != 0) {
            throw new IllegalArgumentException(file + ":" + lineNumber
                    + ": unterminated quote or escape");
        }
        if (active) result.add(word.toString());
        return result;
    }

    private static void validateHeapLimit(long heapLimit) {
        long maximum = Runtime.getRuntime().maxMemory();
        if (heapLimit <= 0L || heapLimit >= maximum) {
            throw new IllegalArgumentException("max heap memory limit " + heapLimit
                    + " must be positive and strictly lower than JVM -Xmx " + maximum);
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return failure.getClass().getName() + (value == null ? "" : ": " + value);
    }

    public static void printUsage() {
        System.err.println("  java -jar apkeep-1.0.0.jar -experiment2"
                + " --datasets-file <incr.sh> [--output <experiment-root>]"
                + " [--trial-timeout 6h] [--max-heap-memory 120GB]"
                + " [--cancellation-grace 1m]");
    }

    static final class DatasetSpec {
        final String name;
        final Path directory;

        DatasetSpec(String name, Path directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    static final class Arguments {
        final Path datasetsFile;
        final Path outputRoot;
        final Duration trialTimeout;
        final Duration cancellationGrace;
        final long heapLimitBytes;

        Arguments(Path datasetsFile, Path outputRoot, Duration trialTimeout,
                Duration cancellationGrace, long heapLimitBytes) {
            this.datasetsFile = datasetsFile;
            this.outputRoot = outputRoot;
            this.trialTimeout = trialTimeout;
            this.cancellationGrace = cancellationGrace;
            this.heapLimitBytes = heapLimitBytes;
        }

        static Arguments parse(String[] args) {
            if (args.length == 0 || !"-experiment2".equals(args[0])) {
                throw new IllegalArgumentException("-experiment2 is required");
            }
            Path datasets = null;
            Path output = null;
            Duration timeout = DEFAULT_TIMEOUT;
            Duration grace = DEFAULT_GRACE;
            long heap = DEFAULT_HEAP_LIMIT;
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("incomplete option: " + option);
                }
                String value = args[++index];
                if ("--datasets-file".equals(option)) datasets = Paths.get(value);
                else if ("--output".equals(option)) output = Paths.get(value);
                else if ("--trial-timeout".equals(option)) timeout = parseDuration(value);
                else if ("--cancellation-grace".equals(option)) grace = parseDuration(value);
                else if ("--max-heap-memory".equals(option)) heap = parseSize(value);
                else throw new IllegalArgumentException("unknown option: " + option);
            }
            if (datasets == null) {
                throw new IllegalArgumentException("--datasets-file is required");
            }
            return new Arguments(datasets, output, timeout, grace, heap);
        }

        private static Duration parseDuration(String value) {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            long multiplier;
            if (normalized.endsWith("ms")) {
                multiplier = 1L;
                normalized = normalized.substring(0, normalized.length() - 2);
                return Duration.ofMillis(positiveLong(normalized, value));
            }
            if (normalized.endsWith("h")) multiplier = 3600L;
            else if (normalized.endsWith("m")) multiplier = 60L;
            else if (normalized.endsWith("s")) multiplier = 1L;
            else throw new IllegalArgumentException("duration requires ms/s/m/h suffix: " + value);
            normalized = normalized.substring(0, normalized.length() - 1);
            return Duration.ofSeconds(Math.multiplyExact(positiveLong(normalized, value), multiplier));
        }

        private static long parseSize(String value) {
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            String[] suffixes = {"TB", "GB", "MB", "KB", "B"};
            long[] multipliers = {
                    1L << 40, 1L << 30, 1L << 20, 1L << 10, 1L
            };
            for (int index = 0; index < suffixes.length; index++) {
                if (normalized.endsWith(suffixes[index])) {
                    String number = normalized.substring(
                            0, normalized.length() - suffixes[index].length());
                    return Math.multiplyExact(positiveLong(number, value), multipliers[index]);
                }
            }
            throw new IllegalArgumentException("memory size requires B/KB/MB/GB/TB suffix: " + value);
        }

        private static long positiveLong(String value, String original) {
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed <= 0L) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid positive value: " + original, exception);
            }
        }
    }

    static final class TrialOutcome {
        final TrialResult result;
        final int trial;
        final String phase;
        final String status;
        final String error;
        final long peakHeapBytes;

        private TrialOutcome(TrialResult result, int trial, String phase, String status,
                String error, long peakHeapBytes) {
            this.result = result;
            this.trial = trial;
            this.phase = phase;
            this.status = status;
            this.error = error;
            this.peakHeapBytes = peakHeapBytes;
        }

        static TrialOutcome success(TrialResult result, long peak) {
            return new TrialOutcome(result, result.trial,
                    result.trial == 0 ? "WARMUP" : "MEASUREMENT",
                    "SUCCESS", "", peak);
        }

        static TrialOutcome failed(int trial, String phase, long peak, Throwable failure) {
            return new TrialOutcome(null, trial, phase, "FAILED", message(failure), peak);
        }

        static TrialOutcome timeout(int trial, String phase, long peak, String error) {
            return new TrialOutcome(null, trial, phase, "TIMEOUT", error, peak);
        }

        static TrialOutcome memoryLimit(int trial, String phase, long peak, String error) {
            return new TrialOutcome(null, trial, phase, "OUT_OF_MEMORY", error, peak);
        }

        boolean successful() {
            return "SUCCESS".equals(status);
        }

        boolean abort() {
            return "TIMEOUT".equals(status) || "OUT_OF_MEMORY".equals(status);
        }
    }

    static final class UnresponsiveTrialException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final TrialOutcome outcome;

        UnresponsiveTrialException(TrialOutcome outcome, String message) {
            super(message);
            this.outcome = outcome;
        }
    }
}
