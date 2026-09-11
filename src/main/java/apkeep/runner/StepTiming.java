package apkeep.runner;

final class StepTiming {
    final int step;
    final int sourceUpdateIndex;
    final long modelNanos;
    final long verificationNanos;
    final long identifyChangesNanos;
    final long totalNanos;

    StepTiming(int step, long modelNanos, long verificationNanos) {
        this(step, step, modelNanos, verificationNanos, 0L,
                modelNanos + verificationNanos);
    }

    StepTiming(int step, long modelNanos, long verificationNanos,
            long identifyChangesNanos) {
        this(step, step, modelNanos, verificationNanos, identifyChangesNanos,
                modelNanos + verificationNanos);
    }

    StepTiming(int step, int sourceUpdateIndex, long modelNanos,
            long verificationNanos, long identifyChangesNanos, long totalNanos) {
        if (step <= 0 || sourceUpdateIndex <= 0 || modelNanos < 0
                || verificationNanos < 0 || identifyChangesNanos < 0
                || totalNanos < modelNanos + verificationNanos) {
            throw new IllegalArgumentException("invalid step timing");
        }
        this.step = step;
        this.sourceUpdateIndex = sourceUpdateIndex;
        this.modelNanos = modelNanos;
        this.verificationNanos = verificationNanos;
        this.identifyChangesNanos = identifyChangesNanos;
        this.totalNanos = totalNanos;
    }
}
