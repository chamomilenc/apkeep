package apkeep.runner;

import apkeep.core.Network;
import apkeep.checker.FullInvariantReport;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Persistent-model mixed workload; deliberately independent of the legacy runners. */
public final class ExperimentSevenRunner {
    static final String SAMPLES = "dataset,method,trial,stage,start_update,end_update,source_start_line,source_end_line,update_count,action,rule_type,scope,model_ns,bdd_migration_ns,verification_ns,total_ns,loops,blackholes,checked_spaces";
    static final String STAGES = "dataset,method,trial,stage,start_update,end_update,update_count,total_ns,mean_ns,p50_ns,p90_ns,p99_ns,first_count,first_mean_ns,first_p50_ns,first_p90_ns,first_p99_ns";
    static final String TRIALS = "dataset,method,phase,trial,status,error,heap_peak_bytes,initialization_ns,post_initialization_ns";

    public static int run(String[] args) {
        try { return execute(Options.parse(args)); }
        catch (Exception error) { System.err.println("Experiment 7: " + error); return 1; }
    }

    static int execute(Options options) throws Exception {
        Properties metadata = new Properties();
        metadata.putAll(options.source);
        metadata.setProperty("source.run.directory", options.input.toString());
        metadata.setProperty("source.run.properties.sha256", MixedInput.sha256(options.input.resolve("run.properties")));
        metadata.setProperty("selected.methods", "apkeep");
        metadata.setProperty("selected.datasets", String.join(",", options.datasets));
        metadata.setProperty("datasets", String.join(",", options.datasets));
        metadata.setProperty("warmup.runs", Integer.toString(options.warmups));
        metadata.setProperty("measurement.runs", Integer.toString(options.measurements));
        metadata.setProperty("trial.timeout", options.timeout.toString());
        metadata.setProperty("heap.limit.bytes", Long.toString(options.heapLimit));
        metadata.setProperty("cancellation.grace", options.grace.toString());
        metadata.setProperty("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        metadata.setProperty("jvm.args", ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        metadata.setProperty("jvm.max.heap.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        metadata.setProperty("max.heap.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        metadata.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        metadata.setProperty("available.processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        metadata.setProperty("semantics", "mixed-prefix-1; selected FIB roots; bound ACL chains; same-interface forwarding allowed");
        metadata.setProperty("counting", "origins with violations, not distinct loops/holes or new-minus-old violations");
        metadata.setProperty("timing", "wall-clock total; creation in initialization; merges in model_ns; no inter-stage GC");
        metadata.setProperty("burst.execution", "serial updates followed by full-space checking");
        metadata.setProperty("heap.sample.interval", "PT0.1S");
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(options.output);
        Path output = Files.createDirectory(options.output.resolve(id));
        metadata.setProperty("run.id", id);
        metadata.setProperty("results.directory", output.toString());
        metadata.setProperty("started.at", Instant.now().toString());
        metadata.setProperty("state", "RUNNING");
        save(output, metadata);
        System.out.println("Experiment 7 output: " + output);
        Thread owner = Thread.currentThread();
        Thread shutdown = new Thread(() -> {
            owner.interrupt();
            try { owner.join(options.grace.toMillis() + 1000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "apkeep-experiment7-run-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        int failures = 0, successes = 0;
        try (BufferedWriter updates = writer(output, "updates.csv", SAMPLES);
             BufferedWriter batches = writer(output, "batches.csv", SAMPLES);
             BufferedWriter stages = writer(output, "stages.csv", STAGES);
             BufferedWriter trials = writer(output, "trials.csv", TRIALS)) {
            for (String dataset : options.datasets) {
                checkpoint();
                Path input = options.input.resolve("inputs").resolve(dataset).normalize();
                MixedInput.require(input.startsWith(options.input.resolve("inputs")), "unsafe dataset path");
                MixedInput data;
                try { data = new MixedInput(input, dataset, options.source); }
                catch (Exception failure) {
                    failures++;
                    write(trials, csv(dataset, "apkeep", "INPUT", 0, "FAILED", failure.toString(), 0, 0, 0));
                    System.err.println(dataset + ": input validation failed: " + failure);
                    continue;
                }
                metadata.setProperty("dataset." + dataset + ".input.manifest", input.resolve("manifest.properties").toString());
                metadata.setProperty("dataset." + dataset + ".input.sha256", MixedInput.sha256(input.resolve("manifest.properties")));
                metadata.setProperty("dataset." + dataset + ".forwarding.origins", String.join(",", data.forwarding));
                metadata.setProperty("dataset." + dataset + ".duplicate.inserts", Long.toString(data.duplicateInserts));
                metadata.setProperty("dataset." + dataset + ".invalid.deletes", Long.toString(data.invalidDeletes));
                metadata.setProperty("dataset." + dataset + ".bdd.table.size", Integer.toString(data.data.parameters.bddTableSize));
                metadata.setProperty("dataset." + dataset + ".merge.ap", Boolean.toString(data.data.parameters.mergeAP));
                save(output, metadata);
                System.out.println(dataset + ": validated " + data.updates.size() + " updates; "
                        + data.forwarding.size() + " forwarding origins");
                if (options.validateOnly) continue;
                for (int run = 0; run < options.warmups + options.measurements; run++) {
                    boolean warmup = run < options.warmups;
                    int trial = warmup ? run + 1 : run - options.warmups + 1;
                    String phase = warmup ? "WARMUP" : "MEASUREMENT";
                    Outcome outcome = monitor(() -> replay(data, trial), options);
                    Trial result = outcome.result;
                    write(trials, csv(dataset, "apkeep", phase, trial, outcome.status, outcome.error,
                            outcome.peak, result == null ? 0 : result.initialization,
                            result == null ? 0 : result.postInitialization));
                    System.out.println(dataset + " " + phase + " " + trial + ": " + outcome.status);
                    if (result != null) {
                        if (!warmup) {
                            for (String row : result.updates) write(updates, row);
                            for (String row : result.batches) write(batches, row);
                            for (String row : result.stages) write(stages, row);
                            successes++;
                        }
                    } else {
                        failures++;
                        if (outcome.fatal) throw new IllegalStateException("worker did not stop; aborting run: " + outcome.error);
                        if (outcome.status.equals("CANCELLED")) throw new CancellationException("trial cancelled");
                        if (warmup || outcome.status.equals("TIMEOUT") || outcome.status.equals("OUT_OF_MEMORY")) break;
                    }
                }
            }
            metadata.setProperty("state", failures == 0 ? options.validateOnly ? "VALIDATED" : "COMPLETED" : "FAILED");
        } catch (Throwable failure) {
            metadata.setProperty("state", Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED");
            metadata.setProperty("error", failure.toString());
            throw failure;
        } finally {
            metadata.setProperty("successes", Integer.toString(successes));
            metadata.setProperty("failures", Integer.toString(failures));
            metadata.setProperty("finished.at", Instant.now().toString());
            save(output, metadata);
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* The hook is waiting for this owner. */ }
        }
        return failures == 0 ? 0 : 1;
    }

    static Trial replay(MixedInput input, int trial) throws Exception {
        Trial result = new Trial();
        long start = System.nanoTime();
        Network network = input.newNetwork();
        long creation = System.nanoTime() - start;
        try {
            for (MixedInput.Stage stage : input.stages) {
                checkpoint();
                long stageStart = System.nanoTime();
                List<Long> latencies = new ArrayList<>();
                long model = stage.start == 1 ? creation : 0;
                for (int index = stage.start; index <= stage.end; index++) {
                    checkpoint();
                    long updateStart = System.nanoTime();
                    Network.AppliedUpdate effect = network.applyUpdateModel(input.updates.get(index - 1));
                    long modelTime = System.nanoTime() - updateStart;
                    FullInvariantReport report = new FullInvariantReport(0, 0, 0);
                    long verification = 0;
                    if (!stage.batch) {
                        long checkStart = System.nanoTime();
                        report = network.verifyMixedInvariants(effect);
                        verification = System.nanoTime() - checkStart;
                    }
                    long mergeStart = System.nanoTime();
                    network.finishStandaloneUpdate();
                    modelTime += System.nanoTime() - mergeStart;
                    long total = System.nanoTime() - updateStart;
                    model += modelTime;
                    if (!stage.batch) {
                        latencies.add(total);
                        String[] p = input.updates.get(index - 1).split(" ");
                        result.updates.add(sample(input, trial, stage, index, index,
                                p[0].equals("+") ? "INSERT" : "DELETE",
                                p[1].equals("fwd") ? "ForwardingRule" : "AclRule", "AFFECTED",
                                modelTime, verification, total, report));
                    }
                }
                long elapsed;
                if (stage.batch) {
                    long mergeStart = System.nanoTime();
                    network.finalizeStandaloneModel();
                    model += System.nanoTime() - mergeStart;
                    long checkStart = System.nanoTime();
                    FullInvariantReport report = network.verifyMixedInvariants(null);
                    long verification = System.nanoTime() - checkStart;
                    long total = System.nanoTime() - stageStart + (stage.start == 1 ? creation : 0);
                    elapsed = total;
                    result.batches.add(sample(input, trial, stage, stage.start, stage.end,
                            "MIXED", "MIXED", "FULL_MODEL", model, verification, total, report));
                } else elapsed = System.nanoTime() - stageStart;
                if (stage.start == 1) result.initialization = elapsed; else result.postInitialization += elapsed;
                result.stages.add(stageRow(input.data.datasetName, trial, stage, elapsed, latencies));
            }
            return result;
        } finally { network.close(); }
    }

    private static String sample(MixedInput input, int trial, MixedInput.Stage stage, int start, int end,
            String action, String type, String scope, long model, long verification, long total, FullInvariantReport report) {
        return csv(input.data.datasetName, "apkeep", trial, stage.name, start, end,
                input.sourceLines.get(start - 1), input.sourceLines.get(end - 1), end - start + 1,
                action, type, scope, model, 0, verification, total,
                report.getLoopUnits(), report.getBlackholeUnits(), report.getCheckedUnits());
    }
    private static String stageRow(String dataset, int trial, MixedInput.Stage stage, long total, List<Long> latencies) {
        List<Long> first = latencies.subList(0, Math.min(100, latencies.size()));
        return csv(dataset, "apkeep", trial, stage.name, stage.start, stage.end, stage.end - stage.start + 1,
                total, mean(latencies), percentile(latencies, .5), percentile(latencies, .9), percentile(latencies, .99),
                first.size(), mean(first), percentile(first, .5), percentile(first, .9), percentile(first, .99));
    }
    static double mean(List<Long> values) { return values.stream().mapToDouble(Long::doubleValue).average().orElse(0); }
    static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values); Collections.sort(sorted);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }
    static final class Trial {
        final List<String> updates = new ArrayList<>(), batches = new ArrayList<>(), stages = new ArrayList<>();
        long initialization, postInitialization;
    }

    /** The worker exclusively owns BDD globals until it has terminated, including cleanup. */
    static Outcome monitor(Callable<Trial> task, Options options) throws InterruptedException {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "apkeep-experiment7"); thread.setDaemon(true); return thread;
        });
        Future<Trial> future = worker.submit(task);
        Outcome outcome = new Outcome();
        long start = System.nanoTime();
        Thread shutdown = new Thread(() -> {
            future.cancel(true); worker.shutdownNow();
            try { worker.awaitTermination(options.grace.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "apkeep-experiment7-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        boolean interrupted = false;
        try {
            while (true) {
                long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                outcome.peak = Math.max(outcome.peak, heap);
                if (heap > options.heapLimit) { outcome.status = "OUT_OF_MEMORY"; outcome.error = "heap limit exceeded"; break; }
                if (System.nanoTime() - start >= options.timeout.toNanos()) {
                    outcome.status = "TIMEOUT"; outcome.error = "trial timeout"; break;
                }
                try {
                    outcome.result = future.get(Math.max(1, Math.min(100,
                            TimeUnit.NANOSECONDS.toMillis(options.timeout.toNanos() - (System.nanoTime() - start)))), TimeUnit.MILLISECONDS);
                    outcome.status = "SUCCESS";
                    break;
                } catch (TimeoutException waiting) { /* Recheck resource limits. */ }
                catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    outcome.status = cause instanceof OutOfMemoryError ? "OUT_OF_MEMORY" : "FAILED";
                    outcome.error = cause.toString(); break;
                }
            }
        } catch (InterruptedException cancelled) {
            interrupted = true; outcome.status = "CANCELLED"; outcome.error = "runner interrupted";
        } finally {
            if (outcome.result == null) future.cancel(true);
            worker.shutdownNow();
            long joinStart = System.nanoTime();
            while (!worker.isTerminated()) {
                long remaining = options.grace.toNanos() - (System.nanoTime() - joinStart);
                if (remaining <= 0) break;
                try { worker.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            outcome.fatal = !worker.isTerminated();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* JVM shutdown is already joining the worker. */ }
            if (outcome.fatal) { outcome.result = null; outcome.status = "FAILED"; outcome.error += "; worker did not terminate"; }
            if (interrupted) Thread.currentThread().interrupt();
        }
        return outcome;
    }
    static final class Outcome {
        Trial result; String status = "FAILED", error = ""; long peak; boolean fatal;
    }

    static final class Options {
        Path input, output; Properties source; List<String> datasets;
        int warmups, measurements; Duration timeout, grace; long heapLimit; boolean validateOnly;
        static Options parse(String[] args) throws Exception {
            Map<String, String> flags = new HashMap<>();
            Set<String> allowed = new HashSet<>(Arrays.asList("--input-run", "--output", "--datasets",
                    "--warmup-runs", "--measurement-runs", "--timeout", "--heap-limit-bytes", "--cancellation-grace"));
            boolean validate = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--validate-only")) { validate = true; continue; }
                MixedInput.require(allowed.contains(args[i]) && i + 1 < args.length, "unknown/incomplete option: " + args[i]);
                String key = args[i++];
                MixedInput.require(flags.put(key, args[i]) == null, "duplicate option: " + key);
            }
            MixedInput.require(flags.containsKey("--input-run"), "usage: -experiment7 --input-run DIR [--validate-only]");
            Options o = new Options();
            o.input = Paths.get(flags.get("--input-run")).toRealPath();
            o.source = MixedInput.properties(o.input.resolve("run.properties"));
            MixedInput.require("7".equals(o.source.getProperty("experiment")), "not an experiment-7 run");
            o.output = Paths.get(flags.getOrDefault("--output", o.input.resolve("apkeep").toString())).toAbsolutePath().normalize();
            MixedInput.require(!o.output.startsWith(o.input.resolve("inputs")), "output cannot be inside inputs");
            o.datasets = Arrays.asList(flags.getOrDefault("--datasets", o.source.getProperty("selected.datasets")).split(","));
            MixedInput.require(new HashSet<>(o.datasets).size() == o.datasets.size(), "duplicate dataset");
            for (String name : o.datasets) MixedInput.require(name.matches("[A-Za-z0-9_-]+"), "invalid dataset name");
            o.warmups = Integer.parseInt(flags.getOrDefault("--warmup-runs", o.source.getProperty("warmup.runs", "1")));
            o.measurements = Integer.parseInt(flags.getOrDefault("--measurement-runs", o.source.getProperty("measurement.runs", "1")));
            o.timeout = Duration.parse(flags.getOrDefault("--timeout", o.source.getProperty("trial.timeout", "PT6H")));
            o.grace = Duration.parse(flags.getOrDefault("--cancellation-grace", o.source.getProperty("cancellation.grace", "PT1M")));
            o.heapLimit = Long.parseLong(flags.getOrDefault("--heap-limit-bytes", o.source.getProperty("heap.limit.bytes")));
            o.validateOnly = validate;
            MixedInput.require(o.warmups >= 0 && o.measurements > 0, "invalid trial count");
            MixedInput.require(o.timeout.toNanos() > 0 && o.grace.toMillis() > 0 && o.heapLimit > 0, "invalid resource limit");
            MixedInput.require(validate || o.heapLimit < Runtime.getRuntime().maxMemory(), "heap limit must be below -Xmx; override --heap-limit-bytes for local tests");
            return o;
        }
    }
    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("experiment 7 cancelled");
    }
    private static BufferedWriter writer(Path output, String file, String header) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(output.resolve(file), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        write(writer, header); return writer;
    }
    private static void write(BufferedWriter writer, String row) throws IOException {
        writer.write(row); writer.newLine(); writer.flush();
    }
    private static void save(Path output, Properties metadata) throws IOException {
        try (OutputStream out = Files.newOutputStream(output.resolve("run.properties"))) {
            metadata.store(out, "APKeep persistent-model mixed workload");
        }
    }
    private static String csv(Object... values) {
        StringJoiner row = new StringJoiner(",");
        for (Object value : values) {
            String text = String.valueOf(value);
            row.add(text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                    ? "\"" + text.replace("\"", "\"\"") + "\"" : text);
        }
        return row.toString();
    }
}
