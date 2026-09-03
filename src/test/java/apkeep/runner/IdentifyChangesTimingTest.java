package apkeep.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import apkeep.core.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentifyChangesTimingTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresFibAclAndNativeNatInsertAndRemove() throws Exception {
        DatasetInput input = DatasetInput.load(createDataset(), false);
        Network network = input.newNetwork();
        try {
            assertMeasured(network.applyUpdateModel(
                    "+ fwd r1 167772160 24 p12 24"));
            assertMeasured(network.applyUpdateModel(
                    "- fwd r1 167772160 24 p12 24"));

            String acl = "acl r1_acl access-list acl permit 6 6 any null null null any null null null 100";
            assertMeasured(network.applyUpdateModel("+ " + acl));
            assertMeasured(network.applyUpdateModel("- " + acl));

            String nat = "nat r1 natport 10.0.0.0 24 11.0.0.0 24";
            assertMeasured(network.applyUpdateModel("+ " + nat));
            assertMeasured(network.applyUpdateModel("- " + nat));
        } finally {
            network.close();
        }
    }

    @Test
    void recordsZeroWhenIdentifyChangesIsNotInvoked() throws Exception {
        DatasetInput input = DatasetInput.load(createDataset(), false);
        Network network = input.newNetwork();
        try {
            String rule = "+ fwd r1 167772160 24 p12 24";
            assertMeasured(network.applyUpdateModel(rule));

            Network.AppliedUpdate duplicate = network.applyUpdateModel(rule);
            assertFalse(duplicate.wasIdentifyChangesInvoked());
            assertEquals(0L, duplicate.getIdentifyChangesNanos());

            Network.AppliedUpdate missing = network.applyUpdateModel(
                    "- fwd r1 184549376 24 p12 24");
            assertFalse(missing.wasIdentifyChangesInvoked());
            assertEquals(0L, missing.getIdentifyChangesNanos());

            assertMeasured(network.applyUpdateModel(
                    "+ fwd r1 201326592 24 p12 100"));
            assertMeasured(network.applyUpdateModel(
                    "+ fwd r1 201326592 24 p13 10"));
            Network.AppliedUpdate hidden = network.applyUpdateModel(
                    "- fwd r1 201326592 24 p13 10");
            assertFalse(hidden.wasIdentifyChangesInvoked());
            assertEquals(0L, hidden.getIdentifyChangesNanos());
        } finally {
            network.close();
        }
    }

    private static void assertMeasured(Network.AppliedUpdate update) {
        assertTrue(update.wasIdentifyChangesInvoked());
        assertTrue(update.getIdentifyChangesNanos() >= 0L);
    }

    private Path createDataset() throws Exception {
        Path dataset = Files.createDirectory(temporaryDirectory.resolve("timing"));
        Files.write(dataset.resolve("parameters.json"),
                "{\"BDD_TABLE_SIZE\":100000,\"GC_INTERVAL\":1000}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("topo.txt"),
                "r1 p12 r2 p21\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dataset.resolve("updates"), new byte[0]);
        Path acls = Files.createDirectory(dataset.resolve("acls"));
        Files.write(acls.resolve("r1_acl"), new byte[0]);
        Files.write(dataset.resolve("nat.txt"),
                "r1 natport\n".getBytes(StandardCharsets.UTF_8));
        return dataset;
    }
}
