package apkeep.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetInputTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsUpdatesAndReachabilityFullyIntoMemory() throws Exception {
        Path dataset = createDataset("loaded");
        DatasetInput input = DatasetInput.load(dataset, true);
        assertEquals(2, input.updates.size());
        assertEquals(1, input.reachability.size());
        assertEquals("r1", input.reachability.get(0).source);
    }

    @Test
    void rejectsNatUpdatesEvenWhenEmpty() throws Exception {
        Path dataset = createDataset("nat");
        Files.createFile(dataset.resolve("nat_updates"));
        assertThrows(IOException.class, () -> DatasetInput.load(dataset, false));
    }

    @Test
    void rejectsNonContiguousReachabilityIds() throws Exception {
        Path dataset = createDataset("ids");
        Files.write(dataset.resolve("reachability.txt"),
                "2 167772160 24 r1 r2 true\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> DatasetInput.load(dataset, true));
    }

    private Path createDataset(String name) throws Exception {
        Path dataset = Files.createDirectory(temporaryDirectory.resolve(name));
        Files.write(dataset.resolve("topo.txt"), "r1 p12 r2 p21\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("updates"), (
                "+ fwd r1 167772160 24 p12 24\n"
                + "+ fwd r2 167772160 24 self 24\n").getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("reachability.txt"),
                "1 167772160 24 r1 r2 true\n".getBytes(StandardCharsets.UTF_8));
        return dataset;
    }
}
