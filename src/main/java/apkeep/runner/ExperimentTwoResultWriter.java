package apkeep.runner;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/** MINT-compatible result persistence for the standalone APKeep Experiment 2. */
final class ExperimentTwoResultWriter implements Closeable {
    static final String TRIAL_HEADER =
            "experiment,dataset,method,rule_profile,mode,scale,trial,status,error,requested_updates,"
                    + "effective_updates,candidate_updates,forwarding_updates,acl_updates,"
                    + "snat_updates,dnat_updates,query_count,heap_before_bytes,"
                    + "heap_after_bytes,heap_delta_bytes,heap_limit_bytes,heap_peak_bytes,"
                    + "model_ns,model_finalize_ns,bdd_migration_ns,verification_ns,total_ns,"
                    + "checked_updates,loops,blackholes,reachable,expected_matches,"
                    + "expected_mismatches,structure_metrics";
    static final String SAMPLE_HEADER =
            "dataset,method,rule_profile,mode,scale,trial,step,source_update_file,"
                    + "source_update_index,model_ns,bdd_migration_ns,verification_ns,total_ns";
    static final String SUMMARY_HEADER =
            "experiment,dataset,method,rule_profile,mode,scale,metric,unit,count,min,mean,"
                    + "p50,p90,p95,p99,max,stddev";
    static final String METHOD_SUMMARY_HEADER =
            "experiment,dataset,method,rule_profile,mode,trial,status,error,update_count,"
                    + "mean_ns,p50_ns,p90_ns,p95_ns,p99_ns,max_ns,min_ns";

    private final Path directory;
    private final long heapLimitBytes;
    private final BufferedWriter trials;
    private final BufferedWriter samples;
    private final Map<String, List<String>> publishedRows =
            new TreeMap<String, List<String>>();
    private final Map<String, List<Long>> updateTotals =
            new TreeMap<String, List<Long>>();
    private final Map<SummaryKey, List<Long>> summary =
            new TreeMap<SummaryKey, List<Long>>();

    ExperimentTwoResultWriter(Path directory, long heapLimitBytes) throws IOException {
        this.directory = directory;
        this.heapLimitBytes = heapLimitBytes;
        if (Files.exists(directory)) {
            throw new IOException("run output already exists: " + directory);
        }
        Files.createDirectories(directory);
        trials = Files.newBufferedWriter(directory.resolve("trials.csv"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        trials.write(TRIAL_HEADER);
        trials.newLine();
        OutputStream raw = Files.newOutputStream(
                directory.resolve("incremental-samples.csv.gz"),
                StandardOpenOption.CREATE_NEW);
        samples = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(raw), StandardCharsets.UTF_8));
        samples.write(SAMPLE_HEADER);
        samples.newLine();
    }

    void writeOutcome(DatasetInput input, TrialResult.Counts counts,
            ExperimentTwoRunner.TrialOutcome outcome) throws IOException {
        List<String> row;
        if (outcome.successful()) {
            TrialResult result = outcome.result;
            row = base(input.datasetName, result.trial, "SUCCESS", "",
                    input.requestedUpdates, input.updates.size(), input.candidateUpdates,
                    counts.forwarding, counts.acl);
            add(row, 0, result.heapBefore, result.heapAfter, result.heapDelta(),
                    heapLimitBytes, outcome.peakHeapBytes, result.modelNanos,
                    result.modelFinalizeNanos, 0, result.verificationNanos,
                    result.totalNanos(), result.checkedUpdates, result.loops,
                    result.blackholes, 0, 0, 0,
                    structureMetrics(result.apCount, input.ignoredNatUpdates,
                            counts.nativeNat));
            if (result.trial > 0) {
                List<Long> totals = new ArrayList<Long>(result.steps.size());
                for (StepTiming step : result.steps) {
                    List<String> sample = new ArrayList<String>();
                    add(sample, input.datasetName, "apkeep", "FULL",
                            RunMode.INCREMENTAL_INVARIANTS.name(), "", result.trial,
                            step.step, "UPDATES", step.sourceUpdateIndex,
                            step.modelNanos, 0, step.verificationNanos,
                            step.totalNanos);
                    writeCsv(samples, sample);
                    totals.add(step.totalNanos);
                    metric(input.datasetName, "update_model_ns", "ns", step.modelNanos);
                    metric(input.datasetName, "update_bdd_migration_ns", "ns", 0L);
                    metric(input.datasetName, "update_verification_ns", "ns",
                            step.verificationNanos);
                    metric(input.datasetName, "update_total_ns", "ns", step.totalNanos);
                }
                samples.flush();
                updateTotals.put(input.datasetName, Collections.unmodifiableList(totals));
                metric(input.datasetName, "heap_before_bytes", "bytes", result.heapBefore);
                metric(input.datasetName, "heap_after_bytes", "bytes", result.heapAfter);
                metric(input.datasetName, "heap_delta_bytes", "bytes", result.heapDelta());
                metric(input.datasetName, "heap_peak_bytes", "bytes", outcome.peakHeapBytes);
                metric(input.datasetName, "model_ns", "ns", result.modelNanos);
                metric(input.datasetName, "model_finalize_ns", "ns",
                        result.modelFinalizeNanos);
                metric(input.datasetName, "bdd_migration_ns", "ns", 0L);
                metric(input.datasetName, "verification_ns", "ns",
                        result.verificationNanos);
                metric(input.datasetName, "total_ns", "ns", result.totalNanos());
                metric(input.datasetName, "expected_mismatches", "count", 0L);
                metric(input.datasetName, "structure.ap_count", "count", result.apCount);
                metric(input.datasetName, "structure.ignored_nat_updates", "count",
                        input.ignoredNatUpdates);
            }
        } else {
            row = base(input.datasetName, outcome.trial, outcome.status, outcome.error,
                    input.requestedUpdates, input.updates.size(), input.candidateUpdates,
                    counts.forwarding, counts.acl);
            add(row, 0, "", "", "", heapLimitBytes, outcome.peakHeapBytes,
                    "", "", "", "", "", "", "", "", 0, 0, 0,
                    structureMetrics(0, input.ignoredNatUpdates, counts.nativeNat));
        }
        appendTrial(row);
        preferPublished(input.datasetName, row);
    }

    void writeSkipped(DatasetInput input, TrialResult.Counts counts, int trial,
            ExperimentTwoRunner.TrialOutcome cause) throws IOException {
        String error = "TIMEOUT".equals(cause.status)
                ? "previous trial timed out" : "previous trial reached heap limit";
        List<String> row = base(input.datasetName, trial, "SKIPPED", error,
                input.requestedUpdates, input.updates.size(), input.candidateUpdates,
                counts.forwarding, counts.acl);
        add(row, 0, "", "", "", heapLimitBytes, "", "", "", "", "", "",
                "", "", "", 0, 0, 0,
                structureMetrics(0, input.ignoredNatUpdates, counts.nativeNat));
        appendTrial(row);
        preferPublished(input.datasetName, row);
    }

    void writePreparationFailure(ExperimentTwoRunner.DatasetSpec spec, Throwable failure)
            throws IOException {
        List<String> row = base(spec.name, 0, "FAILED", failureMessage(failure),
                0, 0, 0, 0, 0);
        add(row, 0, "", "", "", heapLimitBytes, "", "", "", "", "", "",
                "", "", "", 0, 0, 0, "");
        appendTrial(row);
        preferPublished(spec.name, row);
    }

    private List<String> base(String dataset, int trial, String status, String error,
            int requested, int effective, int candidate, int forwarding, int acl) {
        List<String> row = new ArrayList<String>();
        add(row, 2, dataset, "apkeep", "FULL",
                RunMode.INCREMENTAL_INVARIANTS.name(), "", trial, status, error,
                requested, effective, candidate, forwarding, acl, 0, 0);
        return row;
    }

    private void appendTrial(List<String> row) throws IOException {
        if (row.size() != 34) {
            throw new IllegalStateException("invalid Experiment 2 trial column count: "
                    + row.size());
        }
        writeCsv(trials, row);
        trials.flush();
    }

    private void preferPublished(String dataset, List<String> candidate) {
        List<String> existing = publishedRows.get(dataset);
        if (existing == null) {
            publishedRows.put(dataset, candidate);
            return;
        }
        boolean existingSkipped = "SKIPPED".equals(existing.get(7));
        boolean candidateSkipped = "SKIPPED".equals(candidate.get(7));
        if (existingSkipped != candidateSkipped) {
            if (existingSkipped) publishedRows.put(dataset, candidate);
            return;
        }
        int existingTrial = Integer.parseInt(existing.get(6));
        int candidateTrial = Integer.parseInt(candidate.get(6));
        if (existingTrial == 0 && candidateTrial > 0) {
            publishedRows.put(dataset, candidate);
        }
    }

    void writeSummary() throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add(SUMMARY_HEADER);
        for (Map.Entry<SummaryKey, List<Long>> entry : summary.entrySet()) {
            LatencyStatistics statistics = LatencyStatistics.of(entry.getValue());
            List<String> row = new ArrayList<String>();
            add(row, 2, entry.getKey().dataset, "apkeep", "FULL",
                    RunMode.INCREMENTAL_INVARIANTS.name(), "",
                    entry.getKey().metric, entry.getKey().unit,
                    statistics.count, (long) statistics.min, statistics.mean,
                    statistics.p50, statistics.p90, statistics.p95,
                    statistics.p99, (long) statistics.max, statistics.stddev);
            lines.add(csvLine(row));
        }
        writeAtomically(directory.resolve("summary.csv"), lines);
    }

    void publishLatest(Path outputRoot) throws IOException {
        List<String> trialsOutput = new ArrayList<String>();
        trialsOutput.add(TRIAL_HEADER);
        for (List<String> row : publishedRows.values()) trialsOutput.add(csvLine(row));
        writeAtomically(outputRoot.resolve("apkeep.csv"), trialsOutput);

        List<String> summaryOutput = new ArrayList<String>();
        summaryOutput.add(METHOD_SUMMARY_HEADER);
        for (Map.Entry<String, List<String>> entry : publishedRows.entrySet()) {
            List<String> row = entry.getValue();
            List<String> values = new ArrayList<String>();
            add(values, row.get(0), row.get(1), row.get(2), row.get(3), row.get(4),
                    row.get(6), row.get(7), row.get(8));
            List<Long> totals = updateTotals.get(entry.getKey());
            if (!"SUCCESS".equals(row.get(7)) || totals == null || totals.isEmpty()) {
                while (values.size() < 16) values.add("");
            } else {
                LatencyStatistics statistics = LatencyStatistics.of(totals);
                add(values, statistics.count, statistics.mean, statistics.p50,
                        statistics.p90, statistics.p95, statistics.p99,
                        (long) statistics.max, (long) statistics.min);
            }
            summaryOutput.add(csvLine(values));
        }
        writeAtomically(outputRoot.resolve("apkeep-summary.csv"), summaryOutput);
    }

    void writeRunProperties(String status, Instant started, Instant completed, String runId,
            List<ExperimentTwoRunner.DatasetSpec> datasets,
            ExperimentTwoRunner.Arguments arguments, String error) throws IOException {
        Properties values = new Properties();
        values.setProperty("experiment", "2");
        values.setProperty("method", "apkeep");
        values.setProperty("rule.profile", "FULL");
        values.setProperty("mode", RunMode.INCREMENTAL_INVARIANTS.name());
        values.setProperty("run.id", runId);
        values.setProperty("status", status);
        values.setProperty("started.at", started.toString());
        if (completed != null) values.setProperty("completed.at", completed.toString());
        if (error != null) values.setProperty("error", error);
        values.setProperty("datasets.file", arguments.datasetsFile.toAbsolutePath().toString());
        List<String> names = new ArrayList<String>();
        for (ExperimentTwoRunner.DatasetSpec dataset : datasets) names.add(dataset.name);
        values.setProperty("datasets", join(names));
        values.setProperty("warmup.runs", "1");
        values.setProperty("measurement.runs", "1");
        values.setProperty("trial.timeout", arguments.trialTimeout.toString());
        values.setProperty("cancellation.grace", arguments.cancellationGrace.toString());
        values.setProperty("heap.limit.bytes", Long.toString(arguments.heapLimitBytes));
        values.setProperty("heap.sample.interval.ms", "100");
        values.setProperty("jvm.max.heap.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        values.setProperty("nat.updates.policy", "IGNORE");
        values.setProperty("java.version", System.getProperty("java.version", "unknown"));
        values.setProperty("os.name", System.getProperty("os.name", "unknown"));
        values.setProperty("available.processors",
                Integer.toString(Runtime.getRuntime().availableProcessors()));
        OutputStream output = Files.newOutputStream(directory.resolve("run.properties"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            values.store(output, "APKeep Experiment 2");
        } finally {
            output.close();
        }
    }

    private void metric(String dataset, String name, String unit, long value) {
        SummaryKey key = new SummaryKey(dataset, name, unit);
        List<Long> values = summary.get(key);
        if (values == null) {
            values = new ArrayList<Long>();
            summary.put(key, values);
        }
        values.add(value);
    }

    private static String structureMetrics(int apCount, int ignoredNat, int nativeNat) {
        return "ap_count=" + apCount + ";ignored_nat_updates=" + ignoredNat
                + ";native_nat_updates=" + nativeNat;
    }

    private static String failureMessage(Throwable failure) {
        return failure.getClass().getName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private static void writeAtomically(Path target, List<String> lines) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void add(List<String> target, Object... values) {
        for (Object value : values) target.add(value == null ? "" : String.valueOf(value));
    }

    private static void writeCsv(BufferedWriter writer, List<String> values) throws IOException {
        writer.write(csvLine(values));
        writer.newLine();
    }

    private static String csvLine(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append(',');
            String value = values.get(index);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                result.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                result.append(value);
            }
        }
        return result.toString();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            trials.close();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            samples.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
        }
        if (failure != null) throw failure;
    }

    private static final class SummaryKey implements Comparable<SummaryKey> {
        final String dataset;
        final String metric;
        final String unit;

        SummaryKey(String dataset, String metric, String unit) {
            this.dataset = dataset;
            this.metric = metric;
            this.unit = unit;
        }

        @Override
        public int compareTo(SummaryKey other) {
            int value = dataset.compareTo(other.dataset);
            if (value != 0) return value;
            value = metric.compareTo(other.metric);
            return value != 0 ? value : unit.compareTo(other.unit);
        }
    }
}
