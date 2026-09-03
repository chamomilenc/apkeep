package apkeep.runner;

final class ReachabilityQuery {
    final int id;
    final long network;
    final int prefixLength;
    final String source;
    final String destination;
    final boolean expected;

    ReachabilityQuery(int id, long network, int prefixLength,
            String source, String destination, boolean expected) {
        this.id = id;
        this.network = network;
        this.prefixLength = prefixLength;
        this.source = source;
        this.destination = destination;
        this.expected = expected;
    }
}
