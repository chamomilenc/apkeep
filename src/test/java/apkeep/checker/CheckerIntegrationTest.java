package apkeep.checker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import apkeep.core.Network;
import apkeep.utils.Parameters;

class CheckerIntegrationTest {
    private Network network;

    @BeforeEach
    void useSmallBddTable() {
        Parameters.resetDefaults();
        Parameters.BDD_TABLE_SIZE = 100000;
    }

    @AfterEach
    void closeNetwork() {
        if (network != null) network.close();
    }

    @Test
    void reportsDownstreamForwardingDefaultAsBlackhole() throws Exception {
        network = network("r1 p12 r2 p21");
        Network.AppliedUpdate update = network.applyUpdateModel(
                "+ fwd r1 167772160 24 p12 24");
        assertEquals(ViolationType.BLACKHOLE, network.verifyUpdate(update).getType());
        network.finishStandaloneUpdate();
    }

    @Test
    void reportsForwardingCycleAsLoop() throws Exception {
        network = network("r1 p12 r2 p21", "r2 p23 r1 p31");
        Network.AppliedUpdate first = network.applyUpdateModel(
                "+ fwd r1 167772160 24 p12 24");
        network.finishStandaloneUpdate();
        Network.AppliedUpdate second = network.applyUpdateModel(
                "+ fwd r2 167772160 24 p23 24");
        assertEquals(ViolationType.LOOP, network.verifyUpdate(second).getType());
        network.finishStandaloneUpdate();
    }

    @Test
    void selfIsNormalTermination() throws Exception {
        network = network("r1 p12 r2 p21");
        Network.AppliedUpdate update = network.applyUpdateModel(
                "+ fwd r1 167772160 24 self 24");
        assertEquals(ViolationType.NONE, network.verifyUpdate(update).getType());
        network.finishStandaloneUpdate();
    }

    @Test
    void aclPermitPropagatesAndDefaultDenyTerminatesNormally() throws Exception {
        Map<String, Set<String>> acls = new HashMap<String, Set<String>>();
        acls.put("r2", new HashSet<String>(Arrays.asList("inACL")));
        network = new Network("test");
        network.initializeNetwork(new ArrayList<String>(Arrays.asList(
                "r1 p12 r2_inACL_p21_in inport",
                "r2_inACL_p21_in permit r2 p21")), null, acls, null, null);
        apply("+ fwd r1 167772160 24 p12 24");
        apply("+ fwd r2 167772160 24 self 24");
        apply("+ acl r2_inACL access-list inACL permit 0 255 any null null null any null null null 65535");
        network.finalizeStandaloneModel();
        assertEquals(true, network.reachableDevices(167772160L, 24, "r1").contains("r2"));
    }

    @Test
    void vlanExpandsToItsPhysicalPort() throws Exception {
        Map<String, Map<String, Set<String>>> vlans = new HashMap<String, Map<String, Set<String>>>();
        Map<String, Set<String>> deviceVlans = new HashMap<String, Set<String>>();
        deviceVlans.put("vlan100", new HashSet<String>(Arrays.asList("p12")));
        vlans.put("r1", deviceVlans);
        network = new Network("test");
        network.initializeNetwork(new ArrayList<String>(Arrays.asList("r1 p12 r2 p21")),
                null, null, vlans, null);
        apply("+ fwd r1 167772160 24 vlan100 24");
        apply("+ fwd r2 167772160 24 self 24");
        network.finalizeStandaloneModel();
        assertEquals(true, network.reachableDevices(167772160L, 24, "r1").contains("r2"));
    }

	private void apply(String update) throws Exception {
		network.applyUpdateModel(update);
		network.finishStandaloneUpdate();
	}

    private Network network(String... links) {
        Network result = new Network("test");
        result.initializeNetwork(new ArrayList<String>(Arrays.asList(links)),
                null, null, null, null);
        return result;
    }
}
