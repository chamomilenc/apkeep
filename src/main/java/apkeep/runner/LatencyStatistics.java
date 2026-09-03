package apkeep.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LatencyStatistics {
    final int count;
    final double min;
    final double mean;
    final double p50;
    final double p90;
    final double p95;
    final double p99;
    final double max;
    final double stddev;

    private LatencyStatistics(int count, double min, double mean, double p50,
            double p90, double p95, double p99, double max, double stddev) {
        this.count = count;
        this.min = min;
        this.mean = mean;
        this.p50 = p50;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
        this.max = max;
        this.stddev = stddev;
    }

    static LatencyStatistics of(List<Long> values) {
        if (values.isEmpty()) return new LatencyStatistics(0, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN);
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        double sum = 0.0;
        for (long value : sorted) sum += value;
        double mean = sum / sorted.size();
        double squared = 0.0;
        for (long value : sorted) {
            double difference = value - mean;
            squared += difference * difference;
        }
        return new LatencyStatistics(sorted.size(), sorted.get(0), mean,
                percentile(sorted, 0.50), percentile(sorted, 0.90),
                percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted.get(sorted.size() - 1), Math.sqrt(squared / sorted.size()));
    }

    private static double percentile(List<Long> sorted, double percentile) {
        if (sorted.size() == 1) return sorted.get(0);
        double position = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }
}
