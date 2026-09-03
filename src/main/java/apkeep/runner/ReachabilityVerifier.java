package apkeep.runner;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import apkeep.core.Network;

final class ReachabilityVerifier {
    private final Network network;
    private final Map<QueryKey, Set<String>> cache = new HashMap<QueryKey, Set<String>>();

    ReachabilityVerifier(Network network) {
        this.network = network;
    }

    ReachabilityReport verify(java.util.List<ReachabilityQuery> queries) {
        long reachable = 0;
        long matches = 0;
        for (ReachabilityQuery query : queries) {
            QueryKey key = new QueryKey(query.network, query.prefixLength, query.source);
            Set<String> destinations = cache.get(key);
            if (destinations == null) {
                destinations = network.reachableDevices(query.network, query.prefixLength, query.source);
                cache.put(key, destinations);
            }
            boolean actual = destinations.contains(query.destination);
            if (actual) reachable++;
            if (actual == query.expected) matches++;
        }
        return new ReachabilityReport(reachable, matches, queries.size() - matches);
    }

    void clear() {
        cache.clear();
    }

    private static final class QueryKey {
        final long network;
        final int prefixLength;
        final String source;

        QueryKey(long network, int prefixLength, String source) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.source = source;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof QueryKey)) return false;
            QueryKey other = (QueryKey) object;
            return network == other.network && prefixLength == other.prefixLength
                    && source.equals(other.source);
        }

        @Override
        public int hashCode() {
            int result = (int) (network ^ (network >>> 32));
            result = 31 * result + prefixLength;
            result = 31 * result + source.hashCode();
            return result;
        }
    }
}
