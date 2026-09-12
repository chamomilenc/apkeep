package apkeep.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import apkeep.core.Network;

/** Fully materialized immutable input shared by all warmup/measurement trials. */
final class DatasetInput {
    final Path directory;
    final String datasetName;
    final ParameterSettings parameters;
    final List<String> topology;
    final List<String> devices;
    final Map<String, Set<String>> deviceAcls;
    final Map<String, Map<String, Set<String>>> vlanPorts;
    final Map<String, Set<String>> deviceNats;
    final List<String> updates;
    final List<Integer> sourceUpdateIndices;
    final int requestedUpdates;
    final int candidateUpdates;
    final int ignoredNatUpdates;
    final List<ReachabilityQuery> reachability;

    private DatasetInput(Path directory, String datasetName, ParameterSettings parameters,
            List<String> topology, List<String> devices,
            Map<String, Set<String>> deviceAcls,
            Map<String, Map<String, Set<String>>> vlanPorts,
            Map<String, Set<String>> deviceNats, List<String> updates,
            List<Integer> sourceUpdateIndices, int requestedUpdates,
            int candidateUpdates, int ignoredNatUpdates,
            List<ReachabilityQuery> reachability) {
        this.directory = directory;
        this.datasetName = datasetName;
        this.parameters = parameters;
        this.topology = topology;
        this.devices = devices;
        this.deviceAcls = deviceAcls;
        this.vlanPorts = vlanPorts;
        this.deviceNats = deviceNats;
        this.updates = updates;
        this.sourceUpdateIndices = sourceUpdateIndices;
        this.requestedUpdates = requestedUpdates;
        this.candidateUpdates = candidateUpdates;
        this.ignoredNatUpdates = ignoredNatUpdates;
        this.reachability = reachability;
    }

    static DatasetInput load(Path input, boolean requireReachability) throws IOException {
        Path directory = input.toRealPath();
        return loadResolved(directory, requireReachability, inferDatasetName(directory), false);
    }

    static DatasetInput load(Path input, boolean requireReachability, String datasetName)
            throws IOException {
        Path directory = input.toRealPath();
        return loadResolved(directory, requireReachability, datasetName, false);
    }

    static DatasetInput loadForExperimentTwo(Path input, String datasetName)
            throws IOException {
        Path directory = input.toRealPath();
        return loadResolved(directory, false, datasetName, true);
    }

    private static DatasetInput loadResolved(Path directory, boolean requireReachability,
            String datasetName, boolean ignoreNatUpdates) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("dataset is not a directory: " + directory);
        }
        List<String> topology = requiredLines(directory.resolve("topo.txt"));
        List<String> updates = requiredLines(directory.resolve("updates"));
        ParameterSettings parameters = ParameterSettings.load(directory, datasetName);
        List<String> devices = optionalLines(directory.resolve("devices.txt"));
        Map<String, Set<String>> acls = readAcls(directory.resolve("acls"),
                datasetName, topology, devices, updates);
        Map<String, Map<String, Set<String>>> vlans = readVlans(directory.resolve("vlan.txt"));
        Map<String, Set<String>> nats = readNats(directory.resolve("nat.txt"));
        List<ReachabilityQuery> queries = requireReachability
                ? readReachability(directory.resolve("reachability.txt"))
                : Collections.<ReachabilityQuery>emptyList();
        if (requireReachability) validateQueryDevices(directory, topology, devices, updates, queries);
        InputMetadata metadata = readMetadata(directory, updates.size(), ignoreNatUpdates);
        return new DatasetInput(directory, datasetName, parameters, topology, devices, acls,
                vlans, nats, updates, metadata.sourceUpdateIndices,
                metadata.requestedUpdates, metadata.candidateUpdates,
                metadata.ignoredNatUpdates, queries);
    }

    private static String inferDatasetName(Path directory) {
        Path parent = directory.getParent();
        if (parent != null && "experiment-inputs".equals(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent().getFileName().toString();
        }
        return directory.getFileName().toString();
    }

    private static InputMetadata readMetadata(Path directory, int updateCount,
            boolean ignoreNatUpdates) throws IOException {
        Properties properties = new Properties();
        Path manifest = directory.resolve("manifest.properties");
        if (Files.isRegularFile(manifest)) {
            java.io.InputStream input = Files.newInputStream(manifest);
            try {
                properties.load(input);
            } finally {
                input.close();
            }
        }
        int requested = integerProperty(properties, "requested.updates", updateCount, manifest);
        int candidate = integerProperty(properties, "candidate.updates", updateCount, manifest);
        int ignoredNat = Files.isRegularFile(directory.resolve("nat_updates"))
                ? readLines(directory.resolve("nat_updates")).size() : 0;
        if (ignoredNat > 0 && !ignoreNatUpdates) {
            throw new IOException("MINT-style nat_updates is not supported: "
                    + directory.resolve("nat_updates"));
        }
        List<Integer> indices = new ArrayList<Integer>();
        Path sourceIndices = directory.resolve("source-indices.txt");
        if (Files.isRegularFile(sourceIndices)) {
            int lineNumber = 0;
            for (String raw : Files.readAllLines(sourceIndices, StandardCharsets.UTF_8)) {
                lineNumber++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\\s+");
                if (fields.length != 2) {
                    throw new IOException(sourceIndices + ":" + lineNumber
                            + ": expected '<source> <line-index>'");
                }
                if (!"UPDATES".equals(fields[0]) && !"NAT_UPDATES".equals(fields[0])) {
                    throw new IOException(sourceIndices + ":" + lineNumber
                            + ": unknown update source " + fields[0]);
                }
                int index = parseInt(fields[1], sourceIndices, lineNumber, "source index");
                if (index <= 0) {
                    throw new IOException(sourceIndices + ":" + lineNumber
                            + ": source index must be positive");
                }
                if ("UPDATES".equals(fields[0])) indices.add(index);
            }
            if (indices.size() != updateCount) {
                throw new IOException(sourceIndices + ": contains " + indices.size()
                        + " UPDATES entries but updates contains " + updateCount + " rules");
            }
        } else {
            for (int index = 1; index <= updateCount; index++) indices.add(index);
        }
        return new InputMetadata(Collections.unmodifiableList(indices), requested,
                candidate, ignoredNat);
    }

    private static int integerProperty(Properties properties, String key, int fallback,
            Path manifest) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IOException(manifest + ": invalid non-negative integer " + key, exception);
        }
    }

    private static final class InputMetadata {
        final List<Integer> sourceUpdateIndices;
        final int requestedUpdates;
        final int candidateUpdates;
        final int ignoredNatUpdates;

        InputMetadata(List<Integer> sourceUpdateIndices, int requestedUpdates,
                int candidateUpdates, int ignoredNatUpdates) {
            this.sourceUpdateIndices = sourceUpdateIndices;
            this.requestedUpdates = requestedUpdates;
            this.candidateUpdates = candidateUpdates;
            this.ignoredNatUpdates = ignoredNatUpdates;
        }
    }

    Network newNetwork() {
        parameters.apply();
        Network network = new Network(parameters.name);
        try {
            network.initializeNetwork(new ArrayList<String>(topology), devices,
                    deviceAcls, vlanPorts, deviceNats);
            return network;
        } catch (RuntimeException failure) {
            network.close();
            throw failure;
        } catch (Error failure) {
            network.close();
            throw failure;
        }
    }

    private static List<String> requiredLines(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("required dataset file is missing: " + path);
        return readLines(path);
    }

    private static List<String> optionalLines(Path path) throws IOException {
        return Files.isRegularFile(path) ? readLines(path) : null;
    }

    private static List<String> readLines(Path path) throws IOException {
        List<String> result = new ArrayList<String>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Set<String>> readAcls(Path directory, String datasetName,
            List<String> topology, List<String> configuredDevices,
            List<String> updates) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) if (Files.isRegularFile(file)) files.add(file);
        }
        Collections.sort(files);
        Map<String, Set<String>> result = new HashMap<String, Set<String>>();
        Set<String> deviceCandidates = forwardingDevices(topology, configuredDevices, updates);
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith("_usage")) continue;
            String device = fileName.substring(0, fileName.length() - "_usage".length());
            deviceCandidates.add(device);
            List<String> usage = readLines(file);
            for (String row : usage) {
                String[] fields = row.split("\\s+");
                if (fields.length < 3) throw new IOException("invalid ACL usage row in " + file + ": " + row);
                for (int index = 2; index < fields.length; index++) addAcl(result, device, fields[index]);
            }
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.endsWith("_usage")) continue;
            String device = longestDevicePrefix(fileName, deviceCandidates);
            String acl;
            if (device != null) {
                acl = fileName.substring(device.length() + 1);
            } else {
                String[] tokens = fileName.split("_");
                int index = "stanford".equals(datasetName) ? 2 : 1;
                if (tokens.length <= index) {
                    throw new IOException("cannot infer ACL device and name from file: " + file);
                }
                device = "stanford".equals(datasetName)
                        ? tokens[0] + "_" + tokens[1] : tokens[0];
                acl = tokens[index];
            }
            addAcl(result, device, acl);
        }
        return result.isEmpty() ? null : result;
    }

    private static void addAcl(Map<String, Set<String>> result, String device, String acl) {
        Set<String> names = result.get(device);
        if (names == null) {
            names = new HashSet<String>();
            result.put(device, names);
        }
        names.add(acl);
    }

    private static String longestDevicePrefix(String fileName, Set<String> candidates) {
        String best = null;
        for (String candidate : candidates) {
            if (fileName.startsWith(candidate + "_")
                    && (best == null || candidate.length() > best.length())) best = candidate;
        }
        return best;
    }

    private static Set<String> forwardingDevices(List<String> topology,
            List<String> configuredDevices, List<String> updates) {
        Set<String> forwarding = new HashSet<String>();
        if (configuredDevices != null) forwarding.addAll(configuredDevices);
        for (String line : topology) {
            String[] tokens = line.split("\\s+");
            if (tokens.length >= 4) {
                if (!isAclApplication(tokens[0])) forwarding.add(tokens[0]);
                if (!isAclApplication(tokens[2])) forwarding.add(tokens[2]);
            }
        }
        for (String update : updates) {
            String[] tokens = update.split("\\s+");
            if (tokens.length >= 3 && "fwd".equals(tokens[1])) forwarding.add(tokens[2]);
        }
        return forwarding;
    }

    private static Map<String, Map<String, Set<String>>> readVlans(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        Map<String, Map<String, Set<String>>> result = new HashMap<String, Map<String, Set<String>>>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 3) throw new IOException(path + ":" + lineNumber + ": invalid VLAN row");
            Map<String, Set<String>> byVlan = result.get(tokens[0]);
            if (byVlan == null) {
                byVlan = new HashMap<String, Set<String>>();
                result.put(tokens[0], byVlan);
            }
            Set<String> ports = new HashSet<String>();
            for (int i = 2; i < tokens.length; i++) ports.add(tokens[i]);
            byVlan.put(tokens[1], ports);
        }
        return result.isEmpty() ? null : result;
    }

    private static Map<String, Set<String>> readNats(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        Map<String, Set<String>> result = new HashMap<String, Set<String>>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length != 2) throw new IOException(path + ":" + lineNumber + ": invalid native NAT row");
            Set<String> ports = result.get(tokens[0]);
            if (ports == null) {
                ports = new HashSet<String>();
                result.put(tokens[0], ports);
            }
            ports.add(tokens[1]);
        }
        return result.isEmpty() ? null : result;
    }

    static List<ReachabilityQuery> loadReachability(Path path) throws IOException {
        return readReachability(path);
    }

    private static List<ReachabilityQuery> readReachability(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("reachability workload is missing: " + path);
        List<ReachabilityQuery> result = new ArrayList<ReachabilityQuery>();
        int fileLine = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            fileLine++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length != 6) throw new IOException(path + ":" + fileLine + ": expected 6 fields");
            int expectedId = result.size() + 1;
            int id = parseInt(tokens[0], path, fileLine, "id");
            if (id != expectedId) throw new IOException(path + ":" + fileLine + ": expected query id " + expectedId);
            long network = parseUnsignedIpv4(tokens[1], path, fileLine);
            int prefixLength = parseInt(tokens[2], path, fileLine, "prefix length");
            if (prefixLength < 0 || prefixLength > 32) throw new IOException(path + ":" + fileLine + ": prefix length must be 0..32");
            long mask = prefixLength == 0 ? 0L : (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
            if ((network & mask) != network) throw new IOException(path + ":" + fileLine + ": network is not prefix-aligned");
            if (tokens[3].equals(tokens[4])) throw new IOException(path + ":" + fileLine + ": source and destination must differ");
            if (!"true".equals(tokens[5]) && !"false".equals(tokens[5])) {
                throw new IOException(path + ":" + fileLine + ": expected must be true or false");
            }
            result.add(new ReachabilityQuery(id, network, prefixLength,
                    tokens[3], tokens[4], Boolean.parseBoolean(tokens[5])));
        }
        if (result.isEmpty()) throw new IOException("reachability workload has no queries: " + path);
        return Collections.unmodifiableList(result);
    }

    private static void validateQueryDevices(Path directory, List<String> topology,
            List<String> configuredDevices, List<String> updates,
            List<ReachabilityQuery> queries) throws IOException {
        Set<String> forwarding = forwardingDevices(topology, configuredDevices, updates);
        for (ReachabilityQuery query : queries) {
            if (!forwarding.contains(query.source)) {
                throw new IOException(directory.resolve("reachability.txt")
                        + ": query " + query.id + " has unknown source device " + query.source);
            }
            if (!forwarding.contains(query.destination)) {
                throw new IOException(directory.resolve("reachability.txt")
                        + ": query " + query.id + " has unknown destination device " + query.destination);
            }
        }
    }

    private static boolean isAclApplication(String node) {
        return node.endsWith("_in") || node.endsWith("_out");
    }

    private static int parseInt(String value, Path path, int line, String label) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IOException(path + ":" + line + ": invalid " + label, e);
        }
    }

    private static long parseUnsignedIpv4(String value, Path path, int line) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0 || parsed > 0xffffffffL) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException(path + ":" + line + ": invalid unsigned IPv4 network", e);
        }
    }
}
