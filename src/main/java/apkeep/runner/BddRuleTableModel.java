package apkeep.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import apkeep.core.APKeeper;
import apkeep.core.ChangeItem;
import apkeep.elements.ACLElement;
import apkeep.elements.Element;
import apkeep.elements.ForwardElement;
import apkeep.elements.NATElement;
import apkeep.rules.Rule;
import common.BDDACLWrapper;

/** Minimal rule-table-only model: no PPM initialization, transfer, merge or verification. */
final class BddRuleTableModel implements AutoCloseable {
    enum ManagerMode { SHARED, PER_ELEMENT }

    static final class UpdateResult {
        final long elapsedNanos;
        final int changeItems;

        UpdateResult(long elapsedNanos, int changeItems) {
            this.elapsedNanos = elapsedNanos;
            this.changeItems = changeItems;
        }
    }

    static final class TimedUpdateFailure extends Exception {
        final long elapsedNanos;

        TimedUpdateFailure(long elapsedNanos, Throwable cause) {
            super(cause);
            this.elapsedNanos = elapsedNanos;
        }
    }

    private final Map<String, Element> elements;
    private final Map<String, BddCapacityAllocator.Capacity> capacities;
    private final List<BDDACLWrapper> ownedManagers;

    BddRuleTableModel(BddRuleTablePlan plan, ManagerMode mode,
            Map<String, BddCapacityAllocator.Capacity> capacities) {
        this.capacities = capacities;
        this.elements = new LinkedHashMap<String, Element>();
        Set<BDDACLWrapper> uniqueManagers = Collections.newSetFromMap(
                new IdentityHashMap<BDDACLWrapper, Boolean>());
        BDDACLWrapper shared = null;
        try {
            if (mode == ManagerMode.SHARED) {
                BddCapacityAllocator.Capacity capacity = capacities.values().iterator().next();
                shared = new BDDACLWrapper(capacity.nodeTableSize, capacity.cacheSize);
                uniqueManagers.add(shared);
            }
            for (Map.Entry<String, BddRuleTablePlan.ElementKind> entry : plan.elements.entrySet()) {
                String name = entry.getKey();
                BddCapacityAllocator.Capacity capacity = capacities.get(name);
                if (capacity == null) throw new IllegalArgumentException("missing capacity for " + name);
                BDDACLWrapper manager = shared;
                if (manager == null) {
                    manager = new BDDACLWrapper(capacity.nodeTableSize, capacity.cacheSize);
                    uniqueManagers.add(manager);
                }
                Element element = newElement(name, entry.getValue());
                APKeeper keeper = new APKeeper(manager);
                element.setAPC(keeper);
                element.initialize();
                elements.put(name, element);
            }
            ownedManagers = new ArrayList<BDDACLWrapper>(uniqueManagers);
        } catch (RuntimeException failure) {
            for (BDDACLWrapper manager : uniqueManagers) manager.CleanUp();
            throw failure;
        } catch (Error failure) {
            for (BDDACLWrapper manager : uniqueManagers) manager.CleanUp();
            throw failure;
        }
    }

    UpdateResult apply(BddRuleTablePlan.Update update) throws TimedUpdateFailure {
        Element element = elements.get(update.elementName);
        if (element == null) {
            throw new TimedUpdateFailure(0L,
                    new IllegalArgumentException("unknown element " + update.elementName));
        }

        final Rule encoded;
        try {
            encoded = element.encodeOneRule(update.source);
        } catch (Throwable failure) {
            throw new TimedUpdateFailure(0L, failure);
        }

        long started = System.nanoTime();
        try {
            List<ChangeItem> changes = update.insertion
                    ? element.insertOneRule(encoded) : element.removeOneRule(encoded);
            long elapsed = System.nanoTime() - started;
            return new UpdateResult(elapsed, changes == null ? 0 : changes.size());
        } catch (Throwable failure) {
            throw new TimedUpdateFailure(System.nanoTime() - started, failure);
        }
    }

    BddCapacityAllocator.Capacity capacity(String elementName) {
        return capacities.get(elementName);
    }

    int managerCount() {
        return ownedManagers.size();
    }

    Element element(String name) {
        return elements.get(name);
    }

    @Override
    public void close() {
        for (BDDACLWrapper manager : ownedManagers) manager.CleanUp();
        ownedManagers.clear();
        elements.clear();
    }

    private static Element newElement(String name, BddRuleTablePlan.ElementKind kind) {
        switch (kind) {
        case FORWARDING:
            return new ForwardElement(name);
        case ACL:
            return new ACLElement(name);
        case NAT:
            return new NATElement(name);
        default:
            throw new IllegalArgumentException("unsupported element kind " + kind);
        }
    }
}
