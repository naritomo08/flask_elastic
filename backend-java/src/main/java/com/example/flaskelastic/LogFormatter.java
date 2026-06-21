package com.example.flaskelastic;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class LogFormatter {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss 'JST'", Locale.ROOT);

    private LogFormatter() {}

    static String formatTimestamp(Object value) {
        if (value == null) return "";
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue()).atZone(JST).format(DISPLAY_TIME);
        }
        if (value instanceof String string) return formatTimestampString(string);
        return String.valueOf(value);
    }

    static String detectLogType(String indexName) {
        if (indexName != null && indexName.contains("authlog")) return "authlog";
        if (indexName != null && indexName.contains("syslog")) return "syslog";
        return "unknown";
    }

    static boolean matchesExactFilters(LogRecord log, Filters filters) {
        if (!filters.host().isBlank() && QueryBuilder.regexPattern(filters.host()) == null
                && !Objects.equals(log.host(), filters.host())) return false;
        return filters.program().isBlank() || QueryBuilder.regexPattern(filters.program()) != null
                || Objects.equals(log.program(), filters.program());
    }

    private static String formatTimestampString(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        for (String candidate : List.of(
                trimmed, trimmed.replace(" UTC", "Z"), trimmed.replace(" ", "T"),
                trimmed.replace(" UTC", "Z").replace(" ", "T")
        )) {
            try {
                return OffsetDateTime.parse(candidate).atZoneSameInstant(JST).format(DISPLAY_TIME);
            } catch (DateTimeParseException ignored) {}
            try {
                return LocalDateTime.parse(candidate).format(DISPLAY_TIME);
            } catch (DateTimeParseException ignored) {}
        }
        return value;
    }
}
