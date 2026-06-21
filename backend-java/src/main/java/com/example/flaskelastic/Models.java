package com.example.flaskelastic;

import java.util.List;
import java.util.Map;

record Filters(String timeFrom, String timeTo, String logType, String host, String program, String message) {
    static Filters empty() {
        return new Filters("", "", "", "", "", "");
    }

    static Filters normalize(Filters filters) {
        return new Filters(
                Values.trim(filters.timeFrom), Values.trim(filters.timeTo), Values.trim(filters.logType),
                Values.trim(filters.host), Values.trim(filters.program), Values.trim(filters.message)
        );
    }

    static Filters normalize(Map<String, String> values) {
        return new Filters(
                Values.trim(values.get("time_from")), Values.trim(values.get("time_to")),
                Values.trim(values.get("log_type")), Values.trim(values.get("host")),
                Values.trim(values.get("program")), Values.trim(values.get("message"))
        );
    }
}

record LogRecord(
        String id, String index, Object eventTime, String displayTime,
        String logType, String host, String program, String msg
) {}

record SearchHit(String id, String index, Map<String, Object> source, Object score) {}
record ElasticSearchResult(int total, List<SearchHit> hits) {}
record LogSearchResult(int total, List<LogRecord> logs) {}

interface QueryClient {
    Map<String, Object> info() throws Exception;
    ElasticSearchResult search(String index, Map<String, Object> query, int page, int size) throws Exception;
}
