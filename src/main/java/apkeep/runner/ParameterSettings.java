package apkeep.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import apkeep.utils.Parameters;

/** Immutable per-run copy of APKeep's legacy global parameters. */
final class ParameterSettings {
    final String name;
    final boolean mergeAP;
    final int bddTableSize;
    final int gcInterval;
    final int totalApThreshold;
    final int lowMergeableApThreshold;
    final int highMergeableApThreshold;
    final int writeResultInterval;
    final int printResultInterval;
    final double fastUpdateThreshold;

    private ParameterSettings(String name, boolean mergeAP, int bddTableSize,
            int gcInterval, int totalApThreshold, int lowMergeableApThreshold,
            int highMergeableApThreshold, int writeResultInterval,
            int printResultInterval, double fastUpdateThreshold) {
        this.name = name;
        this.mergeAP = mergeAP;
        this.bddTableSize = bddTableSize;
        this.gcInterval = gcInterval;
        this.totalApThreshold = totalApThreshold;
        this.lowMergeableApThreshold = lowMergeableApThreshold;
        this.highMergeableApThreshold = highMergeableApThreshold;
        this.writeResultInterval = writeResultInterval;
        this.printResultInterval = printResultInterval;
        this.fastUpdateThreshold = fastUpdateThreshold;
        validate();
    }

    static ParameterSettings load(Path datasetDirectory) throws IOException {
        String datasetName = datasetDirectory.getFileName().toString();
        JSONObject json = new JSONObject();
        Path file = datasetDirectory.resolve("parameters.json");
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) {
                throw new IOException("parameters.json is not a regular file: " + file);
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                try {
                    json = JSON.parseObject(text);
                } catch (RuntimeException e) {
                    throw new IOException("invalid parameters.json: " + file, e);
                }
            }
        }
        String configuredName = stringValue(json, "NAME", datasetName);
        if (configuredName.trim().isEmpty()) configuredName = datasetName;
        return new ParameterSettings(
                configuredName.trim(),
                booleanValue(json, "MergeAP", Parameters.DEFAULT_MERGE_AP),
                intValue(json, "BDD_TABLE_SIZE", Parameters.DEFAULT_BDD_TABLE_SIZE),
                intValue(json, "GC_INTERVAL", Parameters.DEFAULT_GC_INTERVAL),
                intValue(json, "TOTAL_AP_THRESHOLD", Parameters.DEFAULT_TOTAL_AP_THRESHOLD),
                intValue(json, "LOW_MERGEABLE_AP_THRESHOLD", Parameters.DEFAULT_LOW_MERGEABLE_AP_THRESHOLD),
                intValue(json, "HIGH_MERGEABLE_AP_THRESHOLD", Parameters.DEFAULT_HIGH_MERGEABLE_AP_THRESHOLD),
                intValue(json, "WRITE_RESULT_INTERVAL", Parameters.DEFAULT_WRITE_RESULT_INTERVAL),
                intValue(json, "PRINT_RESULT_INTERVAL", Parameters.DEFAULT_PRINT_RESULT_INTERVAL),
                doubleValue(json, "FAST_UPDATE_THRESHOLD", Parameters.DEFAULT_FAST_UPDATE_THRESHOLD));
    }

    void apply() {
        Parameters.resetDefaults();
        Parameters.MergeAP = mergeAP;
        Parameters.BDD_TABLE_SIZE = bddTableSize;
        Parameters.GC_INTERVAL = gcInterval;
        Parameters.TOTAL_AP_THRESHOLD = totalApThreshold;
        Parameters.LOW_MERGEABLE_AP_THRESHOLD = lowMergeableApThreshold;
        Parameters.HIGH_MERGEABLE_AP_THRESHOLD = highMergeableApThreshold;
        Parameters.WRITE_RESULT_INTERVAL = writeResultInterval;
        Parameters.PRINT_RESULT_INTERVAL = printResultInterval;
        Parameters.FAST_UPDATE_THRESHOLD = fastUpdateThreshold;
    }

    private void validate() {
        requirePositive("BDD_TABLE_SIZE", bddTableSize);
        requirePositive("GC_INTERVAL", gcInterval);
        requireNonNegative("TOTAL_AP_THRESHOLD", totalApThreshold);
        requireNonNegative("LOW_MERGEABLE_AP_THRESHOLD", lowMergeableApThreshold);
        requireNonNegative("HIGH_MERGEABLE_AP_THRESHOLD", highMergeableApThreshold);
        requirePositive("WRITE_RESULT_INTERVAL", writeResultInterval);
        requirePositive("PRINT_RESULT_INTERVAL", printResultInterval);
        if (!Double.isFinite(fastUpdateThreshold) || fastUpdateThreshold < 0.0) {
            throw new IllegalArgumentException("FAST_UPDATE_THRESHOLD must be finite and non-negative");
        }
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
    }

    private static void requireNonNegative(String key, int value) {
        if (value < 0) throw new IllegalArgumentException(key + " must be non-negative");
    }

    private static String stringValue(JSONObject json, String key, String fallback) {
        Object value = json.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean booleanValue(JSONObject json, String key, boolean fallback) {
        if (!json.containsKey(key)) return fallback;
        Object value = json.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static int intValue(JSONObject json, String key, int fallback) {
        if (!json.containsKey(key)) return fallback;
        Object value = json.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    private static double doubleValue(JSONObject json, String key, double fallback) {
        if (!json.containsKey(key)) return fallback;
        Object value = json.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be numeric", e);
        }
    }
}
