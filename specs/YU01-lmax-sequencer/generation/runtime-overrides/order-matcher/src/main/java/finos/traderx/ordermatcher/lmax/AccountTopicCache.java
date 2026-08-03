package finos.traderx.ordermatcher.lmax;

/**
 * Output-handler topic cache. Account ids in the demo/test data are dense enough that a
 * fixed array avoids per-event topic string construction in steady state without adding
 * a map or synchronization to the output-handler path.
 */
final class AccountTopicCache {
    private static final int DEFAULT_CAPACITY = 65_536;

    private final String suffix;
    private final String[] topics;

    AccountTopicCache(String suffix) {
        this(suffix, DEFAULT_CAPACITY);
    }

    AccountTopicCache(String suffix, int capacity) {
        this.suffix = suffix;
        this.topics = new String[capacity];
    }

    String topicFor(int accountId) {
        if (accountId < 0 || accountId >= topics.length) {
            return "/accounts/" + accountId + suffix;
        }
        String topic = topics[accountId];
        if (topic == null) {
            topic = "/accounts/" + accountId + suffix;
            topics[accountId] = topic;
        }
        return topic;
    }
}
