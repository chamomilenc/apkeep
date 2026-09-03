package apkeep.checker;

import java.util.Collections;
import java.util.List;

public final class VerificationResult {
    private static final VerificationResult NONE = new VerificationResult(
            ViolationType.NONE, null, Collections.<String>emptyList());

    private final ViolationType type;
    private final Integer atomicPredicate;
    private final List<String> path;

    private VerificationResult(ViolationType type, Integer atomicPredicate, List<String> path) {
        this.type = type;
        this.atomicPredicate = atomicPredicate;
        this.path = path;
    }

    public static VerificationResult none() {
        return NONE;
    }

    public static VerificationResult violation(ViolationType type, int ap, List<String> path) {
        return new VerificationResult(type, ap,
                Collections.unmodifiableList(new java.util.ArrayList<String>(path)));
    }

    public ViolationType getType() {
        return type;
    }

    public Integer getAtomicPredicate() {
        return atomicPredicate;
    }

    public List<String> getPath() {
        return path;
    }

    public boolean isViolation() {
        return type != ViolationType.NONE;
    }
}
