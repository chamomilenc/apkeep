package apkeep.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically partitions the fixed BDD budgets among rule-table elements. */
final class BddCapacityAllocator {
    static final int MIN_NODE_TABLE_SIZE = 10_000;
    static final int TOTAL_CACHE_SIZE = 1_000_000;
    static final int MIN_CACHE_SIZE = 1_000;

    static final class Capacity {
        final int nodeTableSize;
        final int cacheSize;

        Capacity(int nodeTableSize, int cacheSize) {
            this.nodeTableSize = nodeTableSize;
            this.cacheSize = cacheSize;
        }
    }

    private BddCapacityAllocator() {
    }

    static Map<String, Capacity> shared(List<String> elementNames, int nodeTableSize) {
        List<String> names = sortedNames(elementNames);
        Map<String, Capacity> result = new LinkedHashMap<String, Capacity>();
        for (String name : names) {
            result.put(name, new Capacity(nodeTableSize, TOTAL_CACHE_SIZE));
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Capacity> perElement(List<String> elementNames,
            Map<String, Integer> updateCounts, int totalNodeTableSize) {
        List<String> names = sortedNames(elementNames);
        if (names.isEmpty()) throw new IllegalArgumentException("dataset has no APKeep elements");
        int[] nodes = allocate(names, updateCounts, totalNodeTableSize,
                MIN_NODE_TABLE_SIZE, "BDD_TABLE_SIZE");
        int[] caches = allocate(names, updateCounts, TOTAL_CACHE_SIZE,
                MIN_CACHE_SIZE, "BDD operation-cache budget");
        Map<String, Capacity> result = new LinkedHashMap<String, Capacity>();
        for (int index = 0; index < names.size(); index++) {
            result.put(names.get(index), new Capacity(nodes[index], caches[index]));
        }
        return Collections.unmodifiableMap(result);
    }

    private static int[] allocate(List<String> names, Map<String, Integer> updateCounts,
            int totalBudget, int minimum, String label) {
        long required = (long) names.size() * minimum;
        if (totalBudget < required) {
            throw new IllegalArgumentException(label + " " + totalBudget
                    + " is insufficient for " + names.size() + " elements (minimum "
                    + minimum + " each; required " + required + ")");
        }
        long totalWeight = 0;
        for (String name : names) {
            Integer count = updateCounts.get(name);
            if (count != null && count > 0) totalWeight += count;
        }
        boolean equalWeights = totalWeight == 0;
        if (equalWeights) totalWeight = names.size();

        long remaining = totalBudget - required;
        int[] result = new int[names.size()];
        long assigned = required;
        for (int index = 0; index < names.size(); index++) {
            long weight = equalWeights ? 1L : Math.max(0, value(updateCounts, names.get(index)));
            long share = remaining * weight / totalWeight;
            result[index] = (int) (minimum + share);
            assigned += share;
        }
        int leftover = (int) (totalBudget - assigned);
        for (int index = 0; index < leftover; index++) result[index]++;
        return result;
    }

    private static int value(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        return value == null ? 0 : value;
    }

    private static List<String> sortedNames(List<String> elementNames) {
        List<String> names = new ArrayList<String>(elementNames);
        Collections.sort(names);
        return names;
    }
}
