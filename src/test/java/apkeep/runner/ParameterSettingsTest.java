package apkeep.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import apkeep.utils.Parameters;

class ParameterSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileUsesAllDefaultsAndDirectoryName() throws Exception {
        Path dataset = Files.createDirectory(temporaryDirectory.resolve("sample"));
        ParameterSettings settings = ParameterSettings.load(dataset);
        settings.apply();
        assertEquals("sample", settings.name);
        assertTrue(Parameters.MergeAP);
        assertEquals(100000000, Parameters.BDD_TABLE_SIZE);
        assertEquals(100000, Parameters.GC_INTERVAL);
        assertEquals(500, Parameters.TOTAL_AP_THRESHOLD);
        assertEquals(10, Parameters.LOW_MERGEABLE_AP_THRESHOLD);
        assertEquals(50, Parameters.HIGH_MERGEABLE_AP_THRESHOLD);
        assertEquals(1, Parameters.WRITE_RESULT_INTERVAL);
        assertEquals(100000, Parameters.PRINT_RESULT_INTERVAL);
        assertEquals(0.25, Parameters.FAST_UPDATE_THRESHOLD);
    }

    @Test
    void partialFileOverlaysDefaultsAndParsesMergeAp() throws Exception {
        Path dataset = Files.createDirectory(temporaryDirectory.resolve("partial"));
        Files.write(dataset.resolve("parameters.json"),
                "{\"NAME\":\"\",\"MergeAP\":false,\"BDD_TABLE_SIZE\":12345}".getBytes(StandardCharsets.UTF_8));
        ParameterSettings settings = ParameterSettings.load(dataset);
        settings.apply();
        assertEquals("partial", settings.name);
        assertFalse(Parameters.MergeAP);
        assertEquals(12345, Parameters.BDD_TABLE_SIZE);
        assertEquals(100000, Parameters.GC_INTERVAL);
    }

    @Test
    void invalidParameterIsRejected() throws Exception {
        Path dataset = Files.createDirectory(temporaryDirectory.resolve("invalid"));
        Files.write(dataset.resolve("parameters.json"),
                "{\"GC_INTERVAL\":0}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ParameterSettings.load(dataset));
    }
}
