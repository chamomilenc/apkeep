package apkeep.runner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Standalone comparison of shared and per-element BDD managers for rule tables. */
public final class BddRuleTableBenchmarkRunner {
    private static final int WARMUP_RUNS = 1;
    private static final int MEASUREMENT_RUNS = 3;
    private static final String HEADER = "dataset,manager_mode,trial,update_index,operation,rule_type,"
            + "element,manager_node_table_size,manager_cache_size,elapsed_ns,change_items,status,error";

    private BddRuleTableBenchmarkRunner() {
    }

    public static int run(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            rejectMintNatUpdates(parsed.dataset);
            DatasetInput input = DatasetInput.load(parsed.dataset, false);
            Path output = parsed.output == null ? defaultOutput(input) : parsed.output;
            return execute(input, output);
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

    static int execute(DatasetInput input, Path output) throws IOException {
        rejectMintNatUpdates(input.directory);
        input.parameters.apply();
        BddRuleTablePlan plan = BddRuleTablePlan.from(input);
        Map<String, BddCapacityAllocator.Capacity> shared = BddCapacityAllocator.shared(
                plan.elementNames(), input.parameters.bddTableSize);
        Map<String, BddCapacityAllocator.Capacity> perElement = BddCapacityAllocator.perElement(
                plan.elementNames(), plan.updateCounts, input.parameters.bddTableSize);
        prepareOutputDirectory(output);

        boolean failed = false;
        ModeResult sharedResult;
        ModeResult perElementResult;
        Path sharedFile = output.resolve("shared-manager.csv");
        Path perElementFile = output.resolve("per-element-manager.csv");
        try (CsvWriter sharedWriter = new CsvWriter(sharedFile);
                CsvWriter perElementWriter = new CsvWriter(perElementFile)) {
            System.out.println("APKeep BDD rule-table test: dataset="
                    + input.directory.getFileName() + " elements=" + plan.elements.size()
                    + " updates=" + plan.updates.size());
            sharedResult = runMode(input, plan, BddRuleTableModel.ManagerMode.SHARED,
                    shared, sharedWriter, null);
            failed |= !sharedResult.success;
            perElementResult = runMode(input, plan, BddRuleTableModel.ManagerMode.PER_ELEMENT,
                    perElement, perElementWriter, sharedResult.referenceChangeItems);
            failed |= !perElementResult.success;
        }
        System.out.println("Results: " + output.toAbsolutePath());
        return failed ? 1 : 0;
    }

    private static ModeResult runMode(DatasetInput input, BddRuleTablePlan plan,
            BddRuleTableModel.ManagerMode mode,
            Map<String, BddCapacityAllocator.Capacity> capacities,
            CsvWriter writer, List<Integer> crossModeExpected) throws IOException {
        System.out.println("BDD rule-table mode=" + mode + " warmup=" + WARMUP_RUNS
                + " measurements=" + MEASUREMENT_RUNS);
        TrialExecution warmup = runTrial(input, plan, mode, capacities, writer,
                0, false, crossModeExpected);
        if (!warmup.success) return new ModeResult(false, warmup.changeItems);
        List<Integer> reference = Collections.unmodifiableList(
                new ArrayList<Integer>(warmup.changeItems));
        for (int trial = 1; trial <= MEASUREMENT_RUNS; trial++) {
            TrialExecution measured = runTrial(input, plan, mode, capacities, writer,
                    trial, true, reference);
            if (!measured.success) return new ModeResult(false, reference);
            System.out.println("BDD rule-table mode=" + mode + " trial=" + trial
                    + " updates=" + measured.changeItems.size());
        }
        return new ModeResult(true, reference);
    }

    private static TrialExecution runTrial(DatasetInput input, BddRuleTablePlan plan,
            BddRuleTableModel.ManagerMode mode,
            Map<String, BddCapacityAllocator.Capacity> capacities,
            CsvWriter writer, int trial, boolean writeSuccessRows,
            List<Integer> expectedChangeItems) throws IOException {
        input.parameters.apply();
        List<Integer> changes = new ArrayList<Integer>(plan.updates.size());
        BddRuleTableModel model = null;
        try {
            model = new BddRuleTableModel(plan, mode, capacities);
            int updateOffset = 0;
            for (BddRuleTablePlan.Update update : plan.updates) {
                BddCapacityAllocator.Capacity capacity = model.capacity(update.elementName);
                try {
                    BddRuleTableModel.UpdateResult result = model.apply(update);
                    changes.add(result.changeItems);
                    if (expectedChangeItems != null
                            && (updateOffset >= expectedChangeItems.size()
                            || result.changeItems != expectedChangeItems.get(updateOffset))) {
                        String error = "change_items mismatch: expected "
                                + (updateOffset < expectedChangeItems.size()
                                        ? expectedChangeItems.get(updateOffset) : "<missing>")
                                + " but was " + result.changeItems;
                        writer.write(input, mode, trial, update, capacity,
                                result.elapsedNanos, result.changeItems, "FAILED", error);
                        return new TrialExecution(false, changes);
                    }
                    if (writeSuccessRows) {
                        writer.write(input, mode, trial, update, capacity,
                                result.elapsedNanos, result.changeItems, "SUCCESS", "");
                    }
                } catch (BddRuleTableModel.TimedUpdateFailure failure) {
                    writer.write(input, mode, trial, update, capacity,
                            failure.elapsedNanos, null, "FAILED", describe(failure.getCause()));
                    return new TrialExecution(false, changes);
                }
                updateOffset++;
            }
            if (expectedChangeItems != null && changes.size() != expectedChangeItems.size()) {
                throw new IllegalStateException("change_items sequence length mismatch");
            }
            return new TrialExecution(true, changes);
        } catch (Throwable failure) {
            if (plan.updates.isEmpty()) {
                writer.writeRaw(input.directory.getFileName().toString(), mode.name(), trial,
                        0, "", "", "", 0, 0, 0, null, "FAILED", describe(failure));
            } else if (model == null) {
                BddRuleTablePlan.Update update = plan.updates.get(0);
                BddCapacityAllocator.Capacity capacity = capacities.get(update.elementName);
                writer.write(input, mode, trial, update, capacity,
                        0L, null, "FAILED", describe(failure));
            } else {
                failure.printStackTrace(System.err);
            }
            return new TrialExecution(false, changes);
        } finally {
            if (model != null) model.close();
        }
    }

    private static void rejectMintNatUpdates(Path dataset) throws IOException {
        Path path = dataset.resolve("nat_updates");
        if (!Files.isRegularFile(path)) return;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    throw new IllegalArgumentException(
                            "BDD rule-table test does not support MINT nat_updates: " + path);
                }
            }
        }
    }

    private static void prepareOutputDirectory(Path output) throws IOException {
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) {
                throw new IllegalArgumentException("output is not a directory: " + output);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(output)) {
                if (stream.iterator().hasNext()) {
                    throw new IllegalArgumentException("output directory is not empty: " + output);
                }
            }
        } else {
            Files.createDirectories(output);
        }
    }

    private static Path defaultOutput(DatasetInput input) {
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        return Paths.get(System.getProperty("user.dir"), "results",
                input.directory.getFileName().toString(), timestamp + "-bdd-rule-table");
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown failure";
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    public static void printUsage() {
        System.err.println("  java -jar apkeep-1.0.0.jar -bddtest|-bdd <dataset-directory> [--output <directory>]");
    }

    private static final class Arguments {
        final Path dataset;
        final Path output;

        Arguments(Path dataset, Path output) {
            this.dataset = dataset;
            this.output = output;
        }

        static Arguments parse(String[] args) {
            if (args.length < 2) throw new IllegalArgumentException("mode and dataset directory are required");
            if (!"-bddtest".equals(args[0]) && !"-bdd".equals(args[0])) {
                throw new IllegalArgumentException("unknown BDD rule-table mode: " + args[0]);
            }
            Path output = null;
            for (int index = 2; index < args.length; index++) {
                if ("--output".equals(args[index]) && index + 1 < args.length) {
                    output = Paths.get(args[++index]);
                } else {
                    throw new IllegalArgumentException("unknown or incomplete option: " + args[index]);
                }
            }
            return new Arguments(Paths.get(args[1]), output);
        }
    }

    private static final class ModeResult {
        final boolean success;
        final List<Integer> referenceChangeItems;

        ModeResult(boolean success, List<Integer> referenceChangeItems) {
            this.success = success;
            this.referenceChangeItems = referenceChangeItems;
        }
    }

    private static final class TrialExecution {
        final boolean success;
        final List<Integer> changeItems;

        TrialExecution(boolean success, List<Integer> changeItems) {
            this.success = success;
            this.changeItems = changeItems;
        }
    }

    private static final class CsvWriter implements Closeable {
        private final BufferedWriter writer;
        private int unflushedRows;

        CsvWriter(Path file) throws IOException {
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            writer.write(HEADER);
            writer.newLine();
            writer.flush();
            unflushedRows = 0;
        }

        void write(DatasetInput input, BddRuleTableModel.ManagerMode mode, int trial,
                BddRuleTablePlan.Update update, BddCapacityAllocator.Capacity capacity,
                long elapsedNanos, Integer changeItems, String status, String error)
                throws IOException {
            writeRaw(input.directory.getFileName().toString(), mode.name(), trial,
                    update.index, update.insertion ? "INSERT" : "DELETE",
                    update.kind.csvName, update.elementName,
                    capacity == null ? 0 : capacity.nodeTableSize,
                    capacity == null ? 0 : capacity.cacheSize,
                    elapsedNanos, changeItems, status, error);
        }

        void writeRaw(Object... values) throws IOException {
            for (int index = 0; index < values.length; index++) {
                if (index > 0) writer.write(',');
                Object value = values[index];
                writeCell(value == null ? "" : String.valueOf(value));
            }
            writer.newLine();
            unflushedRows++;
            String status = values.length > 11 && values[11] != null
                    ? String.valueOf(values[11]) : "";
            if (!"SUCCESS".equals(status) || unflushedRows >= 10_000) {
                writer.flush();
                unflushedRows = 0;
            }
        }

        private void writeCell(String value) throws IOException {
            boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
            if (!quote) {
                writer.write(value);
                return;
            }
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
