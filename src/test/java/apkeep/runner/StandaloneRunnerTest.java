package apkeep.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandaloneRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void burstReachabilityWritesThreeSuccessfulTrials() throws Exception {
        Path dataset = createDataset();
        Path output = temporaryDirectory.resolve("results");
        DatasetInput input = DatasetInput.load(dataset, true);
        assertEquals(0, StandaloneRunner.execute(input, RunMode.BURST_REACHABILITY, output));

        List<String> trials = Files.readAllLines(output.resolve("trials.csv"), StandardCharsets.UTF_8);
        assertEquals(4, trials.size());
        assertTrue(trials.get(1).contains("BURST_REACHABILITY"));
        assertTrue(trials.get(1).contains(",SUCCESS,"));
        assertTrue(Files.isRegularFile(output.resolve("summary.csv")));
        assertTrue(Files.isRegularFile(output.resolve("run.properties")));
    }

    @Test
    void incrementalWritesPerUpdateSamples() throws Exception {
        Path dataset = createDataset();
        Path output = temporaryDirectory.resolve("incremental-results");
        DatasetInput input = DatasetInput.load(dataset, false);
        assertEquals(0, StandaloneRunner.execute(input, RunMode.INCREMENTAL_INVARIANTS, output));
        assertTrue(Files.size(output.resolve("incremental-samples.csv.gz")) > 0);

        List<String> trials = Files.readAllLines(output.resolve("trials.csv"), StandardCharsets.UTF_8);
        assertTrue(trials.get(0).endsWith(",identify_changes_ns"));
        String[] trialHeader = trials.get(0).split(",", -1);
        String[] firstTrial = trials.get(1).split(",", -1);
        long identifyTotal = longField(trialHeader, firstTrial, "identify_changes_ns");
        long model = longField(trialHeader, firstTrial, "model_ns");
        long verification = longField(trialHeader, firstTrial, "verification_ns");
        long total = longField(trialHeader, firstTrial, "total_ns");
        assertTrue(identifyTotal >= 0 && identifyTotal <= model);
        assertEquals(model + verification, total);

        List<String> samples = readGzip(output.resolve("incremental-samples.csv.gz"));
        assertTrue(samples.get(0).endsWith(",identify_changes_ns"));
        String[] sampleHeader = samples.get(0).split(",", -1);
        long sampleTotal = 0;
        for (int index = 1; index < samples.size(); index++) {
            String[] sample = samples.get(index).split(",", -1);
            if ("1".equals(field(sampleHeader, sample, "trial"))) {
                long sampleIdentify = longField(sampleHeader, sample, "identify_changes_ns");
                assertTrue(sampleIdentify <= longField(sampleHeader, sample, "model_ns"));
                sampleTotal += sampleIdentify;
            }
        }
        assertEquals(identifyTotal, sampleTotal);

        List<String> summary = Files.readAllLines(output.resolve("summary.csv"), StandardCharsets.UTF_8);
        assertTrue(summary.stream().anyMatch(row -> row.contains(",identify_changes_ns,ns,")));
        assertTrue(summary.stream().anyMatch(row -> row.contains(",update_identify_changes_ns,ns,")));
        Properties properties = new Properties();
        try (java.io.InputStream stream = Files.newInputStream(output.resolve("run.properties"))) {
            properties.load(stream);
        }
        assertEquals("true", properties.getProperty("timing.identify_changes.included_in_model"));
    }

    @Test
    void burstRecordsOnlyTrialLevelIdentifyTiming() throws Exception {
        Path dataset = createDataset();
        Path output = temporaryDirectory.resolve("burst-invariant-results");
        DatasetInput input = DatasetInput.load(dataset, false);
        assertEquals(0, StandaloneRunner.execute(input, RunMode.BURST_INVARIANTS, output));

        assertTrue(!Files.exists(output.resolve("incremental-samples.csv.gz")));
        List<String> trials = Files.readAllLines(output.resolve("trials.csv"), StandardCharsets.UTF_8);
        assertTrue(trials.get(0).endsWith(",identify_changes_ns"));
        String[] header = trials.get(0).split(",", -1);
        String[] first = trials.get(1).split(",", -1);
        assertTrue(longField(header, first, "identify_changes_ns")
                <= longField(header, first, "model_ns"));
        List<String> summary = Files.readAllLines(output.resolve("summary.csv"), StandardCharsets.UTF_8);
        assertTrue(summary.stream().anyMatch(row -> row.contains(",identify_changes_ns,ns,")));
        assertTrue(summary.stream().noneMatch(row -> row.contains(",update_identify_changes_ns,ns,")));
    }

    private Path createDataset() throws Exception {
        Path dataset = Files.createDirectory(temporaryDirectory.resolve("tiny"));
        Files.write(dataset.resolve("parameters.json"), (
                "{\"BDD_TABLE_SIZE\":100000,\"GC_INTERVAL\":1000,"
                + "\"PRINT_RESULT_INTERVAL\":1000,\"WRITE_RESULT_INTERVAL\":1}"
                ).getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("topo.txt"), "r1 p12 r2 p21\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("updates"), (
                "+ fwd r1 167772160 24 p12 24\n"
                + "+ fwd r2 167772160 24 self 24\n").getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("reachability.txt"),
                "1 167772160 24 r1 r2 true\n".getBytes(StandardCharsets.UTF_8));
        return dataset;
    }

    private static List<String> readGzip(Path path) throws Exception {
        List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static long longField(String[] header, String[] row, String name) {
        return Long.parseLong(field(header, row, name));
    }

    private static String field(String[] header, String[] row, String name) {
        for (int index = 0; index < header.length; index++) {
            if (name.equals(header[index])) return row[index];
        }
        throw new AssertionError("missing CSV field " + name);
    }
}
