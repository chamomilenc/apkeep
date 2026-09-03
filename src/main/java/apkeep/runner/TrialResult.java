package apkeep.runner;

import java.util.Collections;
import java.util.List;

final class TrialResult {
    final int trial;
    final String status;
    final String error;
    final int forwardingUpdates;
    final int aclUpdates;
    final int nativeNatUpdates;
    final int queryCount;
    final long heapBefore;
    final long heapAfter;
    final long modelNanos;
    final long modelFinalizeNanos;
    final long verificationNanos;
    final long identifyChangesNanos;
    final long checkedUpdates;
    final long loops;
    final long blackholes;
    final long reachable;
    final long expectedMatches;
    final long expectedMismatches;
    final int apCount;
    final List<StepTiming> steps;

    private TrialResult(int trial, String status, String error,
            int forwardingUpdates, int aclUpdates, int nativeNatUpdates,
            int queryCount, long heapBefore, long heapAfter,
            long modelNanos, long modelFinalizeNanos, long verificationNanos,
            long identifyChangesNanos,
            long checkedUpdates, long loops, long blackholes, long reachable,
            long expectedMatches, long expectedMismatches, int apCount,
            List<StepTiming> steps) {
        this.trial = trial;
        this.status = status;
        this.error = error;
        this.forwardingUpdates = forwardingUpdates;
        this.aclUpdates = aclUpdates;
        this.nativeNatUpdates = nativeNatUpdates;
        this.queryCount = queryCount;
        this.heapBefore = heapBefore;
        this.heapAfter = heapAfter;
        this.modelNanos = modelNanos;
        this.modelFinalizeNanos = modelFinalizeNanos;
        this.verificationNanos = verificationNanos;
        this.identifyChangesNanos = identifyChangesNanos;
        this.checkedUpdates = checkedUpdates;
        this.loops = loops;
        this.blackholes = blackholes;
        this.reachable = reachable;
        this.expectedMatches = expectedMatches;
        this.expectedMismatches = expectedMismatches;
        this.apCount = apCount;
        this.steps = steps == null ? Collections.<StepTiming>emptyList() : steps;
    }

    static TrialResult success(int trial, Counts counts, int queryCount,
            long heapBefore, long heapAfter, long modelNanos,
            long modelFinalizeNanos, long verificationNanos,
            long identifyChangesNanos,
            long checkedUpdates, long loops, long blackholes, long reachable,
            long expectedMatches, long expectedMismatches, int apCount,
            List<StepTiming> steps) {
        return new TrialResult(trial, "SUCCESS", "", counts.forwarding,
                counts.acl, counts.nativeNat, queryCount, heapBefore, heapAfter,
                modelNanos, modelFinalizeNanos, verificationNanos,
                identifyChangesNanos,
                checkedUpdates, loops, blackholes, reachable, expectedMatches,
                expectedMismatches, apCount, steps);
    }

    static TrialResult failed(int trial, Counts counts, int queryCount, Throwable failure) {
        String message = failure.getClass().getName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return new TrialResult(trial, "FAILED", message, counts.forwarding,
                counts.acl, counts.nativeNat, queryCount, -1, -1, 0, 0, 0,
                0,
                0, 0, 0, 0, 0, 0, 0, Collections.<StepTiming>emptyList());
    }

    long totalNanos() {
        return modelNanos + verificationNanos;
    }

    long heapDelta() {
        return heapAfter < 0 || heapBefore < 0 ? -1 : heapAfter - heapBefore;
    }

    boolean successful() {
        return "SUCCESS".equals(status);
    }

    static final class Counts {
        int forwarding;
        int acl;
        int nativeNat;

        static Counts from(java.util.List<String> updates) {
            Counts result = new Counts();
            for (String update : updates) {
                String[] tokens = update.trim().split("\\s+");
                if (tokens.length < 2) continue;
                if ("fwd".equals(tokens[1])) result.forwarding++;
                else if ("acl".equals(tokens[1])) result.acl++;
                else if ("nat".equals(tokens[1])) result.nativeNat++;
            }
            return result;
        }
    }
}
