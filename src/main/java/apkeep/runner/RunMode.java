package apkeep.runner;

enum RunMode {
    INCREMENTAL_INVARIANTS,
    BURST_INVARIANTS,
    BURST_REACHABILITY;

    boolean incremental() {
        return this == INCREMENTAL_INVARIANTS;
    }

    boolean reachability() {
        return this == BURST_REACHABILITY;
    }
}
