package apkeep.runner;

final class StepTiming {
    final int step;
    final long modelNanos;
    final long verificationNanos;
    final long identifyChangesNanos;

    StepTiming(int step, long modelNanos, long verificationNanos) {
        this(step, modelNanos, verificationNanos, 0L);
    }

    StepTiming(int step, long modelNanos, long verificationNanos,
            long identifyChangesNanos) {
        this.step = step;
        this.modelNanos = modelNanos;
        this.verificationNanos = verificationNanos;
        this.identifyChangesNanos = identifyChangesNanos;
    }
}
