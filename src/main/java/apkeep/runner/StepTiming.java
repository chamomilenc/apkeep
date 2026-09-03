package apkeep.runner;

final class StepTiming {
    final int step;
    final long modelNanos;
    final long verificationNanos;

    StepTiming(int step, long modelNanos, long verificationNanos) {
        this.step = step;
        this.modelNanos = modelNanos;
        this.verificationNanos = verificationNanos;
    }
}
