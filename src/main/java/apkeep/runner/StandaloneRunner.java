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
    private static final int WARMUP_RUNS = 1;
    private static final int MEASUREMENT_RUNS = 3;

    private StandaloneRunner() {
    }

    public static int run(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            DatasetInput input = DatasetInput.load(parsed.dataset, parsed.mode.reachability());
            Path output = parsed.output != null ? parsed.output : defaultOutput(input, parsed.mode);
            return execute(input, parsed.mode, output);
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
        Instant started = Instant.now();
        String inputHash = inputHash(input);
        TrialResult.Counts counts = TrialResult.Counts.from(input.updates);
        boolean failed = false;
        try (ResultWriter writer = new ResultWriter(output,
                input.directory.getFileName().toString(), mode)) {
            writer.writeRunProperties(input, "RUNNING", inputHash, started, null);
            System.out.println("APKeep standalone: dataset=" + input.directory.getFileName()
                    + " mode=" + mode + " updates=" + input.updates.size()
                    + " queries=" + input.reachability.size());
            for (int warmup = 1; warmup <= WARMUP_RUNS; warmup++) {
                System.out.println("Warmup " + warmup + "/" + WARMUP_RUNS);
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
            for (int trial = 1; trial <= MEASUREMENT_RUNS; trial++) {
                System.out.println("Measurement trial " + trial + "/" + MEASUREMENT_RUNS);
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

    private static TrialResult runTrial(DatasetInput input, RunMode mode, int trial,
            TrialResult.Counts counts) throws Exception {
        long[] stepModelTimes = mode.incremental() ? new long[input.updates.size()] : null;
        long[] stepVerificationTimes = mode.incremental() ? new long[input.updates.size()] : null;
        stableGc();
        long heapBefore = usedHeap();
        Network network = null;
        try {
            long modelNanos = 0;
            long verificationNanos = 0;
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
                String update = input.updates.get(index);
                start = System.nanoTime();
                Network.AppliedUpdate effect = network.applyUpdateModel(update);
                long stepModel = System.nanoTime() - start;
                long stepVerification = 0;
                if (mode.incremental()) {
                    start = System.nanoTime();
                    VerificationResult result = network.verifyUpdate(effect);
                    stepVerification = System.nanoTime() - start;
                    verificationNanos += stepVerification;
                    checked++;
                    if (result.getType() == ViolationType.LOOP) loops++;
                    else if (result.getType() == ViolationType.BLACKHOLE) blackholes++;
                }
                start = System.nanoTime();
                network.finishStandaloneUpdate();
                stepModel += System.nanoTime() - start;
                modelNanos += stepModel;
                if (mode.incremental()) {
                    stepModelTimes[index] = stepModel;
                    stepVerificationTimes[index] = stepVerification;
                }
            }
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
            List<StepTiming> steps;
            if (mode.incremental()) {
                steps = new ArrayList<StepTiming>(input.updates.size());
                for (int index = 0; index < input.updates.size(); index++) {
                    steps.add(new StepTiming(index + 1, stepModelTimes[index],
                            stepVerificationTimes[index]));
                }
            } else {
                steps = java.util.Collections.emptyList();
            }
            return TrialResult.success(trial, counts, input.reachability.size(),
                    heapBefore, heapAfter, modelNanos, modelFinalizeNanos,
                    verificationNanos, checked, loops, blackholes, reachable,
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
        System.err.println("  java -jar apkeep-1.0.0.jar -incr <dataset-directory> [--output <directory>]");
        System.err.println("  java -jar apkeep-1.0.0.jar -brust|-burst <dataset-directory> --verify invariants|reachability [--output <directory>]");
    }

    private static final class Arguments {
        final RunMode mode;
        final Path dataset;
        final Path output;

        Arguments(RunMode mode, Path dataset, Path output) {
            this.mode = mode;
            this.dataset = dataset;
            this.output = output;
        }

        static Arguments parse(String[] args) {
            if (args.length < 2) throw new IllegalArgumentException("mode and dataset directory are required");
            boolean incremental = "-incr".equals(args[0]);
            boolean burst = "-brust".equals(args[0]) || "-burst".equals(args[0]);
            if (!incremental && !burst) throw new IllegalArgumentException("unknown mode: " + args[0]);
            Path dataset = Paths.get(args[1]);
            Path output = null;
            String verification = null;
            for (int index = 2; index < args.length; index++) {
                String option = args[index];
                if ("--output".equals(option) && index + 1 < args.length) {
                    output = Paths.get(args[++index]);
                } else if ("--verify".equals(option) && index + 1 < args.length) {
                    verification = args[++index];
                } else {
                    throw new IllegalArgumentException("unknown or incomplete option: " + option);
                }
            }
            if (incremental) {
                if (verification != null) throw new IllegalArgumentException("--verify is only valid for Burst");
                return new Arguments(RunMode.INCREMENTAL_INVARIANTS, dataset, output);
            }
            if (verification == null) throw new IllegalArgumentException("Burst requires --verify invariants|reachability");
            if ("invariants".equals(verification)) {
                return new Arguments(RunMode.BURST_INVARIANTS, dataset, output);
            }
            if ("reachability".equals(verification)) {
                return new Arguments(RunMode.BURST_REACHABILITY, dataset, output);
            }
            throw new IllegalArgumentException("unknown Burst verification: " + verification);
        }
    }
}
