package apkeep.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Fully parsed element roster and update routing for the BDD rule-table test. */
final class BddRuleTablePlan {
    enum ElementKind {
        FORWARDING("fwd"), ACL("acl"), NAT("nat");

        final String csvName;

        ElementKind(String csvName) {
            this.csvName = csvName;
        }
    }

    static final class Update {
        final int index;
        final String source;
        final boolean insertion;
        final ElementKind kind;
        final String elementName;

        Update(int index, String source, boolean insertion,
                ElementKind kind, String elementName) {
            this.index = index;
            this.source = source;
            this.insertion = insertion;
            this.kind = kind;
            this.elementName = elementName;
        }
    }

    final Map<String, ElementKind> elements;
    final List<Update> updates;
    final Map<String, Integer> updateCounts;

    private BddRuleTablePlan(Map<String, ElementKind> elements,
            List<Update> updates, Map<String, Integer> updateCounts) {
        this.elements = Collections.unmodifiableMap(new TreeMap<String, ElementKind>(elements));
        this.updates = Collections.unmodifiableList(new ArrayList<Update>(updates));
        this.updateCounts = Collections.unmodifiableMap(new TreeMap<String, Integer>(updateCounts));
    }

    static BddRuleTablePlan from(DatasetInput input) {
        Map<String, ElementKind> elements = new TreeMap<String, ElementKind>();
        for (String line : input.topology) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 4) throw new IllegalArgumentException("invalid topology row: " + line);
            if (!isAclApplication(fields[0])) add(elements, fields[0], ElementKind.FORWARDING, line);
            if (!isAclApplication(fields[2])) add(elements, fields[2], ElementKind.FORWARDING, line);
        }
        if (input.devices != null) {
            for (String device : input.devices) add(elements, device, ElementKind.FORWARDING, device);
        }
        if (input.deviceAcls != null) {
            for (Map.Entry<String, Set<String>> entry : input.deviceAcls.entrySet()) {
                for (String acl : entry.getValue()) {
                    add(elements, entry.getKey() + "_" + acl, ElementKind.ACL, acl);
                }
            }
        }
        if (input.deviceNats != null) {
            for (Map.Entry<String, Set<String>> entry : input.deviceNats.entrySet()) {
                for (String port : entry.getValue()) {
                    add(elements, entry.getKey() + "_" + port, ElementKind.NAT, port);
                }
            }
        }

        List<Update> updates = new ArrayList<Update>(input.updates.size());
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (int index = 0; index < input.updates.size(); index++) {
            String source = input.updates.get(index);
            String[] fields = source.trim().split("\\s+");
            if (fields.length < 3) {
                throw invalidUpdate(index, source, "expected at least three fields");
            }
            boolean insertion;
            if ("+".equals(fields[0])) insertion = true;
            else if ("-".equals(fields[0])) insertion = false;
            else throw invalidUpdate(index, source, "operation must be + or -");

            ElementKind kind;
            String elementName;
            if ("fwd".equals(fields[1])) {
                if (fields.length != 7) throw invalidUpdate(index, source, "fwd update must have 7 fields");
                kind = ElementKind.FORWARDING;
                elementName = fields[2];
            } else if ("acl".equals(fields[1])) {
                if (fields.length < 4) throw invalidUpdate(index, source, "acl update is incomplete");
                kind = ElementKind.ACL;
                elementName = fields[2];
            } else if ("nat".equals(fields[1])) {
                if (fields.length != 8) throw invalidUpdate(index, source, "native nat update must have 8 fields");
                kind = ElementKind.NAT;
                elementName = fields[2] + "_" + fields[3];
            } else {
                throw invalidUpdate(index, source, "unsupported rule type " + fields[1]);
            }
            add(elements, elementName, kind, source);
            Integer old = counts.get(elementName);
            counts.put(elementName, old == null ? 1 : old + 1);
            updates.add(new Update(index + 1, source, insertion, kind, elementName));
        }
        if (elements.isEmpty()) throw new IllegalArgumentException("dataset has no APKeep elements");
        return new BddRuleTablePlan(elements, updates, counts);
    }

    List<String> elementNames() {
        return new ArrayList<String>(elements.keySet());
    }

    private static void add(Map<String, ElementKind> elements, String name,
            ElementKind kind, String source) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("empty element name in " + source);
        }
        ElementKind previous = elements.get(name);
        if (previous != null && previous != kind) {
            throw new IllegalArgumentException("element " + name + " is both "
                    + previous.csvName + " and " + kind.csvName + " in " + source);
        }
        elements.put(name, kind);
    }

    private static IllegalArgumentException invalidUpdate(int zeroBasedIndex,
            String source, String message) {
        return new IllegalArgumentException("updates:" + (zeroBasedIndex + 1)
                + ": " + message + ": " + source);
    }

    private static boolean isAclApplication(String node) {
        return node.endsWith("_in") || node.endsWith("_out");
    }
}
