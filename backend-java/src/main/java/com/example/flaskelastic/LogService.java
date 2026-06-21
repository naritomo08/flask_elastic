package com.example.flaskelastic;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LogService {
    private LogService() {}

    static LogSearchResult search(
            QueryClient client, Config config, Filters filters, int page, int size, Clock clock
    ) throws Exception {
        ElasticSearchResult result = client.search(
                indexPatternForLogType(config, filters.logType()), QueryBuilder.build(filters), page, size
        );
        List<LogRecord> logs = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
            LogRecord log = new LogRecord(
                    hit.id(), hit.index(), source.get("@timestamp"),
                    LogFormatter.formatTimestamp(source.get("@timestamp")),
                    LogFormatter.detectLogType(hit.index()),
                    Values.string(source.get("host")), Values.string(source.get("program")),
                    Values.string(source.get("msg"))
            );
            if (LogFormatter.matchesExactFilters(log, filters)) logs.add(log);
        }
        return new LogSearchResult(result.total(), logs);
    }

    private static String indexPatternForLogType(Config config, String logType) {
        return App.LOG_TYPES.contains(logType) ? "logs-" + logType + "-*" : config.elasticsearchIndex();
    }
}
