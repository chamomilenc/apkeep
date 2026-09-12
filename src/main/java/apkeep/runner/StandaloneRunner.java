package apkeep.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import apkeep.checker.FullInvariantReport;
import apkeep.checker.VerificationResult;
import apkeep.checker.ViolationType;
import apkeep.core.Network;

public final class StandaloneRunner {
    static final int DEFAULT_WARMUP_RUNS = 1;
    static final int DEFAULT_MEASUREMENT_RUNS = 3;

    private StandaloneRunner() {
    }

    public static int run(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            DatasetInput input = DatasetInput.load(parsed.dataset, parsed.mode.reachability());
            Path output = parsed.output != null ? parsed.output : defaultOutput(input, parsed.mode);
            return execute(input, parsed.mode, output, parsed.warmupRuns, parsed.measurementRuns);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            printUsage();
            return 2;
        } catch (Throwable e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    static int execute(DatasetInput input, RunMode mode, Path output) throws IOException {
        return execute(input, mode, output, DEFAULT_WARMUP_RUNS, DEFAULT_MEASUREMENT_RUNS);
    }

    static int execute(DatasetInput input, RunMode mode, Path output,
            int warmupRuns, int measurementRuns) throws IOException {
        if (warmupRuns < 0 || measurementRuns <= 0) {
            throw new IllegalArgumentException("invalid trial count");
        }
        Instant started = Instant.now();
        String inputHash = inputHash(input);
        TrialResult.Counts counts = TrialResult.Counts.from(input.updates);
        boolean failed = false;
        try (ResultWriter writer = new ResultWriter(output,
                input.directory.getFileName().toString(), mode, warmupRuns, measurementRuns)) {
            writer.writeRunProperties(input, "RUNNING", inputHash, started, null);
            System.out.println("APKeep standalone: dataset=" + input.directory.getFileName()
                    + " mode=" + mode + " updates=" + input.updates.size()
                    + " queries=" + input.reachability.size()
                    + " warmup_runs=" + warmupRuns
                    + " measurement_runs=" + measurementRuns);
            for (int warmup = 1; warmup <= warmupRuns; warmup++) {
                System.out.println("Warmup " + warmup + "/" + warmupRuns);
                try {
                    runTrial(input, mode, 0, counts);
                } catch (Throwable failure) {
                    TrialResult result = TrialResult.failed(0, counts,
                            input.reachability.size(), failure);
                    writer.writeTrial(result, input.updates.size());
                    writer.writeSummary();
                    writer.writeRunProperties(input, "FAILED", inputHash, started, Instant.now());
                    failure.printStackTrace(System.err);
                    return 1;
                }
            }
            for (int trial = 1; trial <= measurementRuns; trial++) {
                System.out.println("Measurement trial " + trial + "/" + measurementRuns);
                TrialResult result;
                try {
                    result = runTrial(input, mode, trial, counts);
                } catch (Throwable failure) {
                    failed = true;
                    result = TrialResult.failed(trial, counts,
                            input.reachability.size(), failure);
                    failure.printStackTrace(System.err);
                }
                writer.writeTrial(result, input.updates.size());
                System.out.println("Trial " + trial + " status=" + result.status
                        + " model_ms=" + result.modelNanos / 1_000_000.0
                        + " verification_ms=" + result.verificationNanos / 1_000_000.0);
            }
            writer.writeSummary();
            writer.writeRunProperties(input, failed ? "COMPLETED_WITH_FAILURES" : "COMPLETED",
                    inputHash, started, Instant.now());
        }
        System.out.println("Results: " + output.toAbsolutePath());
        return failed ? 1 : 0;
    }

    static TrialResult runTrial(DatasetInput input, RunMode mode, int trial,
            TrialResult.Counts counts) throws Exception {
        List<StepTiming> steps = mode.incremental()
                ? new ArrayList<StepTiming>(input.updates.size())
                : java.util.Collections.<StepTiming>emptyList();
        stableGc();
        long heapBefore = usedHeap();
        Network network = null;
        try {
            long modelNanos = 0;
            long verificationNanos = 0;
            long identifyChangesNanos = 0;
            long modelFinalizeNanos;
            long checked = 0;
            long loops = 0;
            long blackholes = 0;
            long reachable = 0;
            long matches = 0;
            long mismatches = 0;

            long start = System.nanoTime();
            network = input.newNetwork();
            modelNanos += System.nanoTime() - start;
            for (int index = 0; index < input.updates.size(); index++) {
                checkInterrupted();
                String update = input.updates.get(index);
                try {
                    long stepStarted = System.nanoTime();
                    start = System.nanoTime();
                    Network.AppliedUpdate effect = network.applyUpdateModel(update);
                    long stepModel = System.nanoTime() - start;
                    long stepIdentifyChanges = effect.getIdentifyChangesNanos();
                    long stepVerification = 0;
                    VerificationResult result = null;
                    if (mode.incremental()) {
                        start = System.nanoTime();
                        result = network.verifyUpdate(effect);
                        stepVerification = System.nanoTime() - start;
                    }
                    start = System.nanoTime();
                    network.finishStandaloneUpdate();
                    stepModel += System.nanoTime() - start;
                    modelNanos += stepModel;
                    identifyChangesNanos += stepIdentifyChanges;
                    if (mode.incremental()) {
                        verificationNanos += stepVerification;
                        checked++;
                        if (result.getType() == ViolationType.LOOP) loops++;
                        else if (result.getType() == ViolationType.BLACKHOLE) blackholes++;
                        steps.add(new StepTiming(index + 1, input.sourceUpdateIndices.get(index),
                                stepModel, stepVerification, stepIdentifyChanges,
                                System.nanoTime() - stepStarted));
                    }
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    System.err.println("skipping update " + (index + 1) + "/"
                            + input.updates.size() + ": " + update + " ("
                            + failure.getClass().getName()
                            + (failure.getMessage() == null ? "" : ": " + failure.getMessage())
                            + ")");
                }
            }
            checkInterrupted();
            start = System.nanoTime();
            network.finalizeStandaloneModel();
            modelFinalizeNanos = System.nanoTime() - start;
            modelNanos += modelFinalizeNanos;
            network.clearVerificationState();
            stableGc();
            long heapAfter = usedHeap();

            if (mode == RunMode.BURST_INVARIANTS) {
                start = System.nanoTime();
                FullInvariantReport report = network.verifyAllInvariants();
                verificationNanos = System.nanoTime() - start;
                checked = report.getCheckedUnits();
                loops = report.getLoopUnits();
                blackholes = report.getBlackholeUnits();
            } else if (mode == RunMode.BURST_REACHABILITY) {
                start = System.nanoTime();
                ReachabilityVerifier verifier = new ReachabilityVerifier(network);
                try {
                    ReachabilityReport report = verifier.verify(input.reachability);
                    reachable = report.reachable;
                    matches = report.matches;
                    mismatches = report.mismatches;
                } finally {
                    verifier.clear();
                }
                verificationNanos = System.nanoTime() - start;
            }
            network.clearVerificationState();
            return TrialResult.success(trial, counts, input.reachability.size(),
                    heapBefore, heapAfter, modelNanos, modelFinalizeNanos,
                    verificationNanos, identifyChangesNanos, checked, loops, blackholes, reachable,
                    matches, mismatches, network.getAPNum(), steps);
        } finally {
            if (network != null) network.close();
        }
    }

    private static Path defaultOutput(DatasetInput input, RunMode mode) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        return Paths.get(System.getProperty("user.dir"), "results",
                input.directory.getFileName().toString(), timestamp + "-" + mode.name().toLowerCase());
    }

    private static void stableGc() throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(50L);
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("APKeep trial cancelled");
        }
    }

    private static String inputHash(DatasetInput input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, input.topology);
            updateDigest(digest, input.updates);
            for (ReachabilityQuery query : input.reachability) {
                digest.update((query.id + " " + query.network + " " + query.prefixLength
                        + " " + query.source + " " + query.destination + " "
                        + query.expected + "\n").getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, List<String> lines) {
        for (String line : lines) digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar apkeep-1.0.0.jar -incr <dataset-directory> [--output <directory>] [--warmup-runs N] [--measurement-runs N]");
        System.err.println("  java -jar apkeep-1.0.0.jar -brust|-burst <dataset-directory> --verify invariants|reachability [--output <directory>] [--warmup-runs N] [--measurement-runs N]");
        ExperimentTwoRunner.printUsage();
    }

    private static final class Arguments {
        final RunMode mode;
        final Path dataset;
        final Path output;
        final int warmupRuns;
        final int measurementRuns;

        Arguments(RunMode mode, Path dataset, Path output, int warmupRuns, int measurementRuns) {
            this.mode = mode;
            this.dataset = dataset;
            this.output = output;
            this.warmupRuns = warmupRuns;
            this.measurementRuns = measurementRuns;
        }

        static Arguments parse(String[] args) {
            if (args.length < 2) throw new IllegalArgumentException("mode and dataset directory are required");
            boolean incremental = "-incr".equals(args[0]);
            boolean burst = "-brust".equals(args[0]) || "-burst".equals(args[0]);
            if (!incremental && !burst) throw new IllegalArgumentException("unknown mode: " + args[0]);
            Path dataset = Paths.get(args[1]);
            Path output = null;
            String verification = null;
            int warmupRuns = DEFAULT_WARMUP_RUNS;
            int measurementRuns = DEFAULT_MEASUREMENT_RUNS;
            for (int index = 2; index < args.length; index++) {
                String option = args[index];
                if ("--output".equals(option) && index + 1 < args.length) {
                    output = Paths.get(args[++index]);
                } else if ("--verify".equals(option) && index + 1 < args.length) {
                    verification = args[++index];
                } else if ("--warmup-runs".equals(option) && index + 1 < args.length) {
                    warmupRuns = parseCount(option, args[++index]);
                } else if ("--measurement-runs".equals(option) && index + 1 < args.length) {
                    measurementRuns = parseCount(option, args[++index]);
                } else {
                    throw new IllegalArgumentException("unknown or incomplete option: " + option);
                }
            }
            if (warmupRuns < 0 || measurementRuns <= 0) {
                throw new IllegalArgumentException("invalid trial count");
            }
            if (incremental) {
                if (verification != null) throw new IllegalArgumentException("--verify is only valid for Burst");
                return new Arguments(RunMode.INCREMENTAL_INVARIANTS, dataset, output,
                        warmupRuns, measurementRuns);
            }
            if (verification == null) throw new IllegalArgumentException("Burst requires --verify invariants|reachability");
            if ("invariants".equals(verification)) {
                return new Arguments(RunMode.BURST_INVARIANTS, dataset, output,
                        warmupRuns, measurementRuns);
            }
            if ("reachability".equals(verification)) {
                return new Arguments(RunMode.BURST_REACHABILITY, dataset, output,
                        warmupRuns, measurementRuns);
            }
            throw new IllegalArgumentException("unknown Burst verification: " + verification);
        }

        private static int parseCount(String option, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer: " + value);
            }
        }
    }
}
