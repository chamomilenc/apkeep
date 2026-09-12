package apkeep.runner;

import apkeep.core.Network;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** Read-only adapter for MINT's mixed-prefix-1 inputs. No sampling or rule preload. */
final class MixedInput {
    static final String[] STAGES = {"initialization", "incremental-1", "burst-1",
            "incremental-2", "burst-2", "incremental-3"};
    final DatasetInput data;
    final Properties manifest;
    final List<Stage> stages = new ArrayList<>();
    final List<String> updates = new ArrayList<>();
    final List<Integer> sourceLines = new ArrayList<>();
    final Set<String> forwarding = new TreeSet<>();
    final List<String> links = new ArrayList<>();
    final Map<String, String> applications = new TreeMap<>();
    final Map<String, Set<String>> acls = new TreeMap<>();
    final List<ReachabilityQuery> queries;
    long duplicateInserts, invalidDeletes;

    MixedInput(Path directory, String name, Properties config) throws Exception {
        this(directory, name, config, false);
    }

    MixedInput(Path directory, String name, Properties config, boolean requireReachability) throws Exception {
        directory = directory.toRealPath();
        manifest = properties(directory.resolve("manifest.properties"));
        require("mixed-prefix-1".equals(manifest.getProperty("schema")), "unknown input schema");
        require("false".equals(manifest.getProperty("metadata.rules.preloaded")), "preloaded rules unsupported");
        require("false".equals(manifest.getProperty("nat_updates.included")), "NAT sidecar unsupported");
        for (String required : Arrays.asList("updates", "topo.txt", "source-lines.csv"))
            require(manifest.containsKey("sha256." + required), "missing checksum: " + required);
        for (String key : manifest.stringPropertyNames()) if (key.startsWith("sha256.")) {
            Path file = directory.resolve(key.substring(7)).normalize();
            require(file.startsWith(directory) && file.toRealPath().startsWith(directory), "unsafe input path: " + key);
            require(sha256(file).equalsIgnoreCase(manifest.getProperty(key)), "checksum mismatch: " + file);
        }
        // Every file consumed by the generic loader must be covered by the manifest.
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String relative = directory.relativize(file).toString().replace(File.separatorChar, '/');
                if (relative.equals("manifest.properties") || relative.equals("reachability.txt")) continue;
                require(manifest.containsKey("sha256." + relative), "unverified input file: " + relative);
            }
        }
        data = DatasetInput.load(directory, false, name);
        require(data.deviceNats == null || data.deviceNats.isEmpty(), "native NAT metadata unsupported in experiment 7");
        if (data.deviceAcls != null) data.deviceAcls.forEach((d, a) -> acls.put(d, new TreeSet<>(a)));
        int next = 1;
        for (int k = 0; k < STAGES.length; k++) {
            String prefix = "stage." + STAGES[k] + ".";
            int start = Integer.parseInt(manifest.getProperty(prefix + "start"));
            int end = Integer.parseInt(manifest.getProperty(prefix + "end"));
            boolean batch = k % 2 == 0;
            require(start == next && end >= start, "invalid stage bounds: " + STAGES[k]);
            require(Boolean.toString(batch).equals(manifest.getProperty(prefix + "batch")), "invalid batch flag");
            String countKey = k == 0 ? "mixed.initial.update.count" : batch
                    ? "mixed.burst.update.count" : "mixed.incremental.update.count";
            require(end - start + 1 == Integer.parseInt(config.getProperty(countKey)), "stage/config mismatch");
            stages.add(new Stage(STAGES[k], start, end, batch));
            next = end + 1;
        }
        require(data.updates.size() == next - 1, "update count does not match stage schedule");
        require(data.updates.size() == Integer.parseInt(manifest.getProperty("updates")), "manifest update count mismatch");
        List<String> mapping = Files.readAllLines(directory.resolve("source-lines.csv"), StandardCharsets.UTF_8);
        require(mapping.size() == next && mapping.get(0).equals("update_index,source_physical_line,stage,action,rule_type"),
                "invalid source-lines.csv header/count");
        Map<String, String> active = new HashMap<>();
        int stageIndex = 0, previousLine = 0;
        for (int j = 0; j < data.updates.size(); j++) {
            if (j + 1 > stages.get(stageIndex).end) stageIndex++;
            String line = data.updates.get(j).trim().replaceAll("\\s+", " ");
            String[] p = line.split(" ");
            require(p.length >= 3 && (p[0].equals("+") || p[0].equals("-")), "invalid update " + (j + 1));
            String type;
            String key;
            if (p[1].equals("fwd")) {
                require(p.length == 7, "expected prefix FIB rule at update " + (j + 1));
                long address = Long.parseLong(p[3]);
                int bits = Integer.parseInt(p[4]), priority = Integer.parseInt(p[6]);
                require(address >= 0 && address <= 0xffffffffL && bits >= 0 && bits <= 32 && priority >= 0,
                        "invalid FIB address/prefix/priority");
                require(bits == 0 ? address == 0 : (address & ((1L << (32 - bits)) - 1)) == 0,
                        "noncanonical FIB prefix");
                forwarding.add(p[2]);
                key = "fwd " + p[2] + " " + p[3] + " " + p[4] + " " + p[6];
                type = "FIB";
            } else if (p[1].equals("acl")) {
                require(p.length == 17, "expected native 14-field ACL rule at update " + (j + 1));
                require(p[5].equals("permit") || p[5].equals("deny"), "unsupported ACL action");
                int priority = Integer.parseInt(p[16]);
                require(priority >= 0, "invalid ACL priority");
                require(acls.entrySet().stream().anyMatch(e -> e.getValue().stream()
                        .anyMatch(a -> (e.getKey() + "_" + a).equals(p[2]))), "undeclared ACL: " + p[2]);
                key = "acl " + p[2] + " " + priority;
                type = "ACL";
            } else throw new IOException("unsupported experiment-7 rule type: " + p[1]);
            String body = line.substring(2), old = active.get(key);
            if (p[0].equals("+")) {
                require(old == null || old.equals(body), "conflicting priority at update " + (j + 1));
                if (old != null) duplicateInserts++;
                active.put(key, body);
            } else if (body.equals(old)) active.remove(key); else invalidDeletes++;
            updates.add(line);
            String[] row = mapping.get(j + 1).split(",", -1);
            require(row.length == 5 && Integer.parseInt(row[0]) == j + 1, "invalid update index mapping");
            int physical = Integer.parseInt(row[1]);
            require(physical > previousLine && row[2].equals(stages.get(stageIndex).name)
                    && row[3].equals(p[0].equals("+") ? "INSERT" : "DELETE") && row[4].equals(type),
                    "source mapping mismatch at update " + (j + 1));
            sourceLines.add(physical);
            previousLine = physical;
        }
        Path reachability = directory.resolve("reachability.txt");
        if (requireReachability) {
            require(Files.isRegularFile(reachability), "reachability workload is missing: " + reachability);
            queries = DatasetInput.loadReachability(reachability);
            int expected = Integer.parseInt(config.getProperty("mixed.reachability.query.count", "1000"));
            require(expected > 0 && queries.size() == expected,
                    "reachability query count mismatch: required " + expected + ", found " + queries.size());
            for (ReachabilityQuery query : queries) {
                require(forwarding.contains(query.source),
                        "unknown reachability source device: " + query.source);
                require(forwarding.contains(query.destination),
                        "unknown reachability destination device: " + query.destination);
            }
        } else {
            queries = Collections.emptyList();
        }
        buildTopology();
    }

    private void buildTopology() throws Exception {
        Set<String> rawNodes = new HashSet<>();
        for (String link : data.topology) {
            String[] p = link.split("\\s+");
            require(p.length == 4, "invalid topology row: " + link);
            rawNodes.add(p[0]); rawNodes.add(p[2]);
        }
        Map<String, List<String>> groups = new TreeMap<>();
        Path aclDir = data.directory.resolve("acls");
        if (Files.isDirectory(aclDir)) try (Stream<Path> stream = Files.list(aclDir)) {
            for (Path file : (Iterable<Path>) stream.sorted()::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith("_usage")) continue;
                String device = name.substring(0, name.length() - 6);
                for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    row = row.trim();
                    if (row.isEmpty() || row.startsWith("#")) continue;
                    String[] p = row.split("\\s+");
                    require(p.length >= 3 && (p[1].equals("in") || p[1].equals("out")), "invalid ACL binding: " + row);
                    List<String> group = groups.computeIfAbsent(device + " " + p[0] + " " + p[1], k -> new ArrayList<>());
                    for (int k = 2; k < p.length; k++) {
                        String table = device + "_" + p[k], node = table + "_" + p[0] + "_" + p[1];
                        require(!forwarding.contains(node), "ACL/FIB node collision");
                        applications.put(node, table);
                        if (!group.contains(node)) group.add(node);
                    }
                }
            }
        }
        for (List<String> group : groups.values()) {
            long explicit = group.stream().filter(rawNodes::contains).count();
            require(explicit == 0 || explicit == group.size(), "partially expanded ACL chain");
            if (explicit > 0) group.clear(); else Collections.reverse(group);
        }
        for (String link : data.topology) {
            String[] p = link.split("\\s+");
            List<String> chain = new ArrayList<>();
            chain.addAll(groups.getOrDefault(p[0] + " " + p[1] + " out", Collections.emptyList()));
            chain.addAll(groups.getOrDefault(p[2] + " " + p[3] + " in", Collections.emptyList()));
            String from = p[0], port = p[1];
            for (String node : chain) {
                links.add(from + " " + port + " " + node + " inport");
                from = node; port = "permit";
            }
            links.add(from + " " + port + " " + p[2] + " " + p[3]);
        }
        // Unattached synthetic bindings are not reachable application positions.
        Set<String> attached = new HashSet<>();
        for (String link : links) { String[] p = link.split(" "); attached.add(p[0]); attached.add(p[2]); }
        Set<String> sources = new HashSet<>();
        for (String link : links) { String[] p = link.split(" "); sources.add(p[0] + " " + p[1]); }
        for (String node : applications.keySet()) {
            if (rawNodes.contains(node) && node.endsWith("_in"))
                require(sources.contains(node + " permit"), "explicit ingress ACL has no permit edge: " + node);
        }
        applications.keySet().retainAll(attached);
    }

    Network newNetwork() {
        data.parameters.apply();
        Network network = new Network(data.parameters.name);
        try {
            network.initializeMixedNetwork(links, forwarding, acls.isEmpty() ? null : acls, data.vlanPorts, applications);
            return network;
        } catch (RuntimeException | Error error) { network.close(); throw error; }
    }

    static Properties properties(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return p;
    }
    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    static final class Stage {
        final String name; final int start, end; final boolean batch;
        Stage(String name, int start, int end, boolean batch) {
            this.name = name; this.start = start; this.end = end; this.batch = batch;
        }
    }
}
