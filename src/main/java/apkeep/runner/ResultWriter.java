package apkeep.runner;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

final class ResultWriter implements Closeable {
    private static final String TRIAL_HEADER = "experiment,dataset,method,rule_profile,mode,scale,trial,status,error,requested_updates,effective_updates,candidate_updates,forwarding_updates,acl_updates,snat_updates,dnat_updates,query_count,heap_before_bytes,heap_after_bytes,heap_delta_bytes,heap_limit_bytes,heap_peak_bytes,model_ns,model_finalize_ns,bdd_migration_ns,verification_ns,total_ns,checked_updates,loops,blackholes,reachable,expected_matches,expected_mismatches,structure_metrics";
    private static final String SAMPLE_HEADER = "dataset,method,rule_profile,mode,scale,trial,step,source_update_file,source_update_index,model_ns,bdd_migration_ns,verification_ns";
    private static final String SUMMARY_HEADER = "experiment,dataset,method,rule_profile,mode,scale,metric,unit,count,min,mean,p50,p90,p95,p99,max,stddev";

    private final Path outputDirectory;
    private final String dataset;
    private final RunMode mode;
    private final BufferedWriter trialWriter;
    private final BufferedWriter sampleWriter;
    private final List<TrialResult> results = new ArrayList<TrialResult>();

    ResultWriter(Path outputDirectory, String dataset, RunMode mode) throws IOException {
        this.outputDirectory = outputDirectory;
        this.dataset = dataset;
        this.mode = mode;
        prepareDirectory(outputDirectory);
        trialWriter = Files.newBufferedWriter(outputDirectory.resolve("trials.csv"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        trialWriter.write(TRIAL_HEADER);
        trialWriter.newLine();
        if (mode.incremental()) {
            OutputStream raw = Files.newOutputStream(outputDirectory.resolve("incremental-samples.csv.gz"),
                    StandardOpenOption.CREATE_NEW);
            sampleWriter = new BufferedWriter(new java.io.OutputStreamWriter(
                    new GZIPOutputStream(raw), StandardCharsets.UTF_8));
            sampleWriter.write(SAMPLE_HEADER);
            sampleWriter.newLine();
        } else {
            sampleWriter = null;
        }
    }

    void writeTrial(TrialResult result, int updateCount) throws IOException {
        results.add(result);
        List<String> row = new ArrayList<String>();
        add(row, "0", dataset, "apkeep", "FULL", mode.name(), "", result.trial,
                result.status, result.error, updateCount, updateCount, updateCount,
                result.forwardingUpdates, result.aclUpdates, 0,
                result.nativeNatUpdates, result.queryCount, result.heapBefore,
                result.heapAfter, result.heapDelta(), -1, -1, result.modelNanos,
                result.modelFinalizeNanos, 0, result.verificationNanos,
                result.totalNanos(), result.checkedUpdates, result.loops,
                result.blackholes, result.reachable, result.expectedMatches,
                result.expectedMismatches, "ap_count=" + result.apCount);
        writeCsv(trialWriter, row);
        trialWriter.flush();
        if (sampleWriter != null && result.successful()) {
            for (StepTiming step : result.steps) {
                row = new ArrayList<String>();
                add(row, dataset, "apkeep", "FULL", mode.name(), "", result.trial,
                        step.step, "updates", step.step, step.modelNanos, 0,
                        step.verificationNanos);
                writeCsv(sampleWriter, row);
            }
            sampleWriter.flush();
        }
    }

    void writeSummary() throws IOException {
        Map<String, List<Long>> metrics = new LinkedHashMap<String, List<Long>>();
        metrics.put("heap_before_bytes", new ArrayList<Long>());
        metrics.put("heap_after_bytes", new ArrayList<Long>());
        metrics.put("heap_delta_bytes", new ArrayList<Long>());
        metrics.put("model_ns", new ArrayList<Long>());
        metrics.put("model_finalize_ns", new ArrayList<Long>());
        metrics.put("bdd_migration_ns", new ArrayList<Long>());
        metrics.put("verification_ns", new ArrayList<Long>());
        metrics.put("total_ns", new ArrayList<Long>());
        List<Long> updateModel = new ArrayList<Long>();
        List<Long> updateVerification = new ArrayList<Long>();
        for (TrialResult result : results) {
            if (!result.successful() || result.trial == 0) continue;
            metrics.get("heap_before_bytes").add(result.heapBefore);
            metrics.get("heap_after_bytes").add(result.heapAfter);
            metrics.get("heap_delta_bytes").add(result.heapDelta());
            metrics.get("model_ns").add(result.modelNanos);
            metrics.get("model_finalize_ns").add(result.modelFinalizeNanos);
            metrics.get("bdd_migration_ns").add(0L);
            metrics.get("verification_ns").add(result.verificationNanos);
            metrics.get("total_ns").add(result.totalNanos());
            for (StepTiming step : result.steps) {
                updateModel.add(step.modelNanos);
                updateVerification.add(step.verificationNanos);
            }
        }
        if (mode.incremental()) {
            metrics.put("update_model_ns", updateModel);
            metrics.put("update_verification_ns", updateVerification);
        }
        Path summary = outputDirectory.resolve("summary.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(summary,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write(SUMMARY_HEADER);
            writer.newLine();
            for (Map.Entry<String, List<Long>> metric : metrics.entrySet()) {
                if (metric.getValue().isEmpty()) continue;
                LatencyStatistics stats = LatencyStatistics.of(metric.getValue());
                List<String> row = new ArrayList<String>();
                String unit = metric.getKey().endsWith("_bytes") ? "bytes" : "ns";
                add(row, "0", dataset, "apkeep", "FULL", mode.name(), "",
                        metric.getKey(), unit, stats.count, stats.min, stats.mean,
                        stats.p50, stats.p90, stats.p95, stats.p99, stats.max,
                        stats.stddev);
                writeCsv(writer, row);
            }
        }
    }

    void writeRunProperties(DatasetInput input, String status, String inputHash,
            Instant started, Instant completed) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("experiment", "0");
        properties.setProperty("dataset", dataset);
        properties.setProperty("dataset.directory", input.directory.toString());
        properties.setProperty("method", "apkeep");
        properties.setProperty("rule.profile", "FULL");
        properties.setProperty("mode", mode.name());
        properties.setProperty("warmup.runs", "1");
        properties.setProperty("measurement.runs", "3");
        properties.setProperty("updates", Integer.toString(input.updates.size()));
        properties.setProperty("queries", Integer.toString(input.reachability.size()));
        properties.setProperty("input.sha256", inputHash);
        properties.setProperty("status", status);
        properties.setProperty("started.at", started.toString());
        if (completed != null) properties.setProperty("completed.at", completed.toString());
        properties.setProperty("java.version", System.getProperty("java.version", "unknown"));
        properties.setProperty("java.vm.name", System.getProperty("java.vm.name", "unknown"));
        properties.setProperty("os.name", System.getProperty("os.name", "unknown"));
        properties.setProperty("os.arch", System.getProperty("os.arch", "unknown"));
        properties.setProperty("available.processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        properties.setProperty("max.heap.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        properties.setProperty("parameter.name", input.parameters.name);
        properties.setProperty("parameter.merge_ap", Boolean.toString(input.parameters.mergeAP));
        properties.setProperty("parameter.bdd_table_size", Integer.toString(input.parameters.bddTableSize));
        properties.setProperty("parameter.gc_interval", Integer.toString(input.parameters.gcInterval));
        try (OutputStream output = Files.newOutputStream(outputDirectory.resolve("run.properties"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "Standalone APKeep run");
        }
    }

    private static void prepareDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) throw new IOException("output is not a directory: " + directory);
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                if (stream.iterator().hasNext()) throw new IOException("output directory is not empty: " + directory);
            }
        } else {
            Files.createDirectories(directory);
        }
    }

    private static void add(List<String> target, Object... values) {
        for (Object value : values) target.add(value == null ? "" : String.valueOf(value));
    }

    private static void writeCsv(BufferedWriter writer, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            String value = values.get(index);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                writer.write('"');
                writer.write(value.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(value);
            }
        }
        writer.newLine();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            trialWriter.close();
        } catch (IOException e) {
            failure = e;
        }
        if (sampleWriter != null) {
            try {
                sampleWriter.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        if (failure != null) throw failure;
    }
}
