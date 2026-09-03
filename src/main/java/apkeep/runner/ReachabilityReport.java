package apkeep.runner;

final class ReachabilityReport {
    final long reachable;
    final long matches;
    final long mismatches;

    ReachabilityReport(long reachable, long matches, long mismatches) {
        this.reachable = reachable;
        this.matches = matches;
        this.mismatches = mismatches;
    }
}
