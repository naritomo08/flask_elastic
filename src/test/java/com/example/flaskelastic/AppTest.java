package com.example.flaskelastic;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-02T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    @Test
    void formatTimestampConvertsEpochMillisToJst() {
        assertEquals("2026/06/02 20:11:55 JST", App.formatTimestamp(1780398715000L));
    }

    @Test
    void formatTimestampKeepsNaiveTimestampAsJst() {
        assertEquals("2026/06/02 20:11:55 JST", App.formatTimestamp("2026-06-02 20:11:55.000"));
    }

    @Test
    void datetimeLocalToIsoTreatsInputAsJst() {
        assertEquals("2026-06-02T11:11:00Z", App.datetimeLocalToIso("2026-06-02T20:11"));
    }

    @Test
    void wildcardValueEscapesElasticsearchWildcards() {
        assertEquals("*a\\\\b\\*c\\?*", App.wildcardValue("a\\b*c?"));
    }

    @Test
    void buildQueryWithMessageProgramHostAndTimeRange() throws Exception {
        App.Filters filters = new App.Filters("2026-06-02T20:00", "2026-06-02T21:00", "syslog", "flink1", "systemd", "sshd");

        Map<String, Object> query = App.buildQuery(filters);

        String queryJson = App.JSON.writeValueAsString(query);
        assertTrue(queryJson.contains("\"match_phrase\""));
        assertTrue(queryJson.contains("\"match\""));
        assertTrue(queryJson.contains("\"query\":\"sshd\""));
        assertTrue(queryJson.contains("\"operator\":\"and\""));
        assertTrue(queryJson.contains("\"host.keyword\""));
        assertTrue(queryJson.contains("\"program.keyword\""));
        assertTrue(queryJson.contains("\"gte\":\"2026-06-02T11:00:00Z\""));
        assertTrue(queryJson.contains("\"lte\":\"2026-06-02T12:00:00Z\""));
    }

    @Test
    void buildQuerySupportsSpaceSeparatedMessageSearch() throws Exception {
        App.Filters filters = App.normalizeFilters(new App.Filters("", "", "", "", "", "authlog forward test from"));

        String queryJson = App.JSON.writeValueAsString(App.buildQuery(filters));

        assertTrue(queryJson.contains("\"match_phrase\""));
        assertTrue(queryJson.contains("\"wildcard\""));
        assertTrue(queryJson.contains("\"msg.keyword\""));
        assertTrue(queryJson.contains("\"value\":\"*authlog forward test from*\""));
        assertTrue(queryJson.contains("\"case_insensitive\":true"));
    }

    @Test
    void searchLogsUsesElasticsearchIndexAndFormatsResult() throws Exception {
        FakeClient client = new FakeClient();
        App.Filters filters = new App.Filters("", "", "syslog", "", "systemd", "sshd");

        List<App.LogRecord> logs = App.searchLogs(client, testConfig(), filters, FIXED_CLOCK);

        assertEquals(1, client.searches.size());
        assertEquals("logs-syslog-*", client.searches.getFirst().index);
        assertEquals("2026/06/02 20:11:55 JST", logs.getFirst().displayTime());
        assertEquals("syslog", logs.getFirst().logType());
        assertEquals("Reached target sshd-keygen.target.", logs.getFirst().msg());
    }

    private static App.Config testConfig() {
        return new App.Config(
                "5000",
                "http://elastic1:9200",
                "logs-*",
                50,
                "static",
                "java_log_search_filters"
        );
    }

    static class FakeClient implements App.QueryClient {
        final List<SearchCall> searches = new java.util.ArrayList<>();

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public List<App.SearchHit> search(String index, Map<String, Object> query) {
            searches.add(new SearchCall(index, query));
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("@timestamp", 1780398715000L);
            source.put("host", "flink1");
            source.put("program", "systemd");
            source.put("msg", "Reached target sshd-keygen.target.");
            source.put("severity", 6);
            return List.of(new App.SearchHit("1", ".ds-logs-syslog-2026.06.02-000001", source, 1.0));
        }
    }

    record SearchCall(String index, Map<String, Object> query) {
    }
}
