package com.example.flaskelastic;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class QueryBuilder {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private QueryBuilder() {}

    static Map<String, Object> build(Filters filters) {
        List<Object> must = new ArrayList<>();
        List<Object> filterClauses = new ArrayList<>();
        if (!filters.message().isBlank()) must.add(textSearchClause("msg", filters.message()));
        if (!filters.host().isBlank()) filterClauses.add(exactOrRegexClause("host", filters.host()));
        if (!filters.program().isBlank()) filterClauses.add(exactOrRegexClause("program", filters.program()));

        Map<String, Object> timeRange = new LinkedHashMap<>();
        if (!filters.timeFrom().isBlank()) timeRange.put("gte", datetimeLocalToIso(filters.timeFrom()));
        if (!filters.timeTo().isBlank()) timeRange.put("lte", datetimeLocalToIso(filters.timeTo()));
        if (!timeRange.isEmpty()) filterClauses.add(Map.of("range", Map.of("@timestamp", timeRange)));
        if (must.isEmpty() && filterClauses.isEmpty()) return Map.of("match_all", Map.of());

        Map<String, Object> bool = new LinkedHashMap<>();
        if (!must.isEmpty()) bool.put("must", must);
        if (!filterClauses.isEmpty()) bool.put("filter", filterClauses);
        return Map.of("bool", bool);
    }

    static String regexPattern(String value) {
        return value != null && value.length() >= 2 && value.startsWith("/") && value.endsWith("/")
                ? value.substring(1, value.length() - 1) : null;
    }

    private static Map<String, Object> textSearchClause(String field, String value) {
        String wildcard = wildcardValue(value);
        return Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("match_phrase", Map.of(field, Map.of("query", value))),
                        Map.of("match", Map.of(field, Map.of("query", value, "operator", "and"))),
                        Map.of("wildcard", Map.of(field, Map.of("value", wildcard, "case_insensitive", true))),
                        Map.of("wildcard", Map.of(field + ".keyword", Map.of("value", wildcard, "case_insensitive", true)))
                ), "minimum_should_match", 1
        ));
    }

    private static Map<String, Object> exactOrRegexClause(String field, String value) {
        String pattern = regexPattern(value);
        if (pattern == null) {
            return Map.of("bool", Map.of(
                    "should", List.of(
                            Map.of("term", Map.of(field + ".keyword", Map.of("value", value))),
                            Map.of("term", Map.of(field, Map.of("value", value)))
                    ), "minimum_should_match", 1
            ));
        }
        return Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("regexp", Map.of(field + ".keyword", Map.of("value", pattern, "case_insensitive", true))),
                        Map.of("regexp", Map.of(field, Map.of("value", pattern, "case_insensitive", true)))
                ), "minimum_should_match", 1
        ));
    }

    private static String wildcardValue(String value) {
        return "*" + value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?") + "*";
    }

    private static String datetimeLocalToIso(String value) {
        try {
            return LocalDateTime.parse(value.trim()).atZone(JST).withZoneSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value.trim()).withOffsetSameInstant(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ignoredAgain) {
                return value;
            }
        }
    }
}
