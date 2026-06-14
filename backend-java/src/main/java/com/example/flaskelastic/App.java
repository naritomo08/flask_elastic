package com.example.flaskelastic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;

public class App {
    static final List<String> LOG_TYPES = List.of("syslog", "authlog");
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss 'JST'", Locale.ROOT);
    static final ObjectMapper JSON = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final Config config;
    private final QueryClient queryClient;
    private final Clock clock;
    private final String indexTemplate;

    public App(Config config, QueryClient queryClient, Clock clock) {
        this.config = config;
        this.queryClient = queryClient;
        this.clock = clock;
        this.indexTemplate = "";
    }

    static String loadResource(String path) {
        try (InputStream is = App.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnv();
        App app = new App(config, new ElasticsearchClient(config), Clock.systemUTC());
        app.start();
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(config.port)), 0);
        server.createContext("/", this::handleApiRoot);
        server.createContext("/health", this::handleHealth);
        server.createContext("/api/options", this::handleApiOptions);
        server.createContext("/api/logs", this::handleApiLogs);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.printf("listening on :%s%n", config.port);
    }

    private void handleApiRoot(HttpExchange exchange) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", "java-elastic-backend");
        payload.put("endpoints", List.of("/health", "/api/options", "/api/logs"));
        sendJson(exchange, 200, payload);
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("POST".equals(method)) {
            Filters filters = normalizeFilters(parseForm(exchange));
            setSearchCookie(exchange, filters);
            redirect(exchange, "/");
            return;
        }
        if (!"GET".equals(method)) {
            sendText(exchange, 405, "method not allowed", "text/plain; charset=utf-8");
            return;
        }

        CookieSearch cookieSearch = popSearchCookie(exchange);
        Filters filters = cookieSearch.filters;
        boolean searched = cookieSearch.searched;
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        if (!query.isEmpty()) {
            filters = normalizeFilters(query);
            searched = true;
        }

        List<LogRecord> logs = List.of();
        String error = "";
        if (searched) {
            try {
                logs = searchLogs(queryClient, config, filters, clock);
            } catch (Exception ex) {
                error = ex.getMessage();
            }
        }

        String html = renderIndex(filters, logs, searched, error);
        sendText(exchange, 200, html, "text/html; charset=utf-8");
    }

    private void handleClear(HttpExchange exchange) throws IOException {
        clearSearchCookie(exchange);
        redirect(exchange, "/");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", queryClient.ping());
        payload.put("elasticsearch_url", config.elasticsearchUrl);
        payload.put("index", config.elasticsearchIndex);
        sendJson(exchange, 200, payload);
    }

    private void handleApiOptions(HttpExchange exchange) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("log_types", LOG_TYPES);
        sendJson(exchange, 200, payload);
    }

    private void handleApiLogs(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            sendText(exchange, 405, "method not allowed", "text/plain; charset=utf-8");
            return;
        }

        try {
            Filters filters = filtersFromRequest(exchange);
            List<LogRecord> logs = searchLogs(queryClient, config, filters, clock);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("filters", filters);
            payload.put("count", logs.size());
            payload.put("logs", logs);
            sendJson(exchange, 200, payload);
        } catch (Exception ex) {
            sendText(exchange, 502, ex.getMessage(), "text/plain; charset=utf-8");
        }
    }

    static List<LogRecord> searchLogs(QueryClient client, Config config, Filters filters, Clock clock) throws Exception {
        List<SearchHit> hits = client.search(indexPatternForLogType(config, filters.logType), buildQuery(filters));
        List<LogRecord> logs = new ArrayList<>();
        for (SearchHit hit : hits) {
            Map<String, Object> source = hit.source == null ? Map.of() : hit.source;
            LogRecord log = new LogRecord(
                    hit.id,
                    hit.index,
                    source.get("@timestamp"),
                    formatTimestamp(source.get("@timestamp")),
                    detectLogType(hit.index),
                    stringValue(source.get("host")),
                    stringValue(source.get("program")),
                    stringValue(source.get("msg"))
            );
            if (logMatchesExactFilters(log, filters)) {
                logs.add(log);
            }
        }
        return logs;
    }

    static Map<String, Object> buildQuery(Filters filters) {
        List<Object> must = new ArrayList<>();
        List<Object> filterClauses = new ArrayList<>();

        if (!filters.message.isBlank()) {
            must.add(textSearchClause("msg", filters.message));
        }
        if (!filters.host.isBlank()) {
            filterClauses.add(exactMatchClause("host", filters.host));
        }
        if (!filters.program.isBlank()) {
            filterClauses.add(exactMatchClause("program", filters.program));
        }

        Map<String, Object> timeRange = new LinkedHashMap<>();
        if (!filters.timeFrom.isBlank()) {
            timeRange.put("gte", datetimeLocalToIso(filters.timeFrom));
        }
        if (!filters.timeTo.isBlank()) {
            timeRange.put("lte", datetimeLocalToIso(filters.timeTo));
        }
        if (!timeRange.isEmpty()) {
            filterClauses.add(Map.of("range", Map.of("@timestamp", timeRange)));
        }

        if (must.isEmpty() && filterClauses.isEmpty()) {
            return Map.of("match_all", Map.of());
        }

        Map<String, Object> bool = new LinkedHashMap<>();
        if (!must.isEmpty()) {
            bool.put("must", must);
        }
        if (!filterClauses.isEmpty()) {
            bool.put("filter", filterClauses);
        }
        return Map.of("bool", bool);
    }

    static Map<String, Object> textSearchClause(String field, String value) {
        return Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("match_phrase", Map.of(field, Map.of("query", value))),
                        Map.of("match", Map.of(field, Map.of("query", value, "operator", "and"))),
                        Map.of("wildcard", Map.of(field, Map.of("value", wildcardValue(value), "case_insensitive", true))),
                        Map.of("wildcard", Map.of(field + ".keyword", Map.of("value", wildcardValue(value), "case_insensitive", true)))
                ),
                "minimum_should_match", 1
        ));
    }

    static Map<String, Object> exactMatchClause(String field, String value) {
        return Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("term", Map.of(field + ".keyword", Map.of("value", value))),
                        Map.of("term", Map.of(field, Map.of("value", value)))
                ),
                "minimum_should_match", 1
        ));
    }

    static String wildcardValue(String value) {
        return "*" + value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?") + "*";
    }

    static String datetimeLocalToIso(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return LocalDateTime.parse(value.trim()).atZone(JST).withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value.trim()).withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ignoredAgain) {
                return value;
            }
        }
    }

    static String indexPatternForLogType(Config config, String logType) {
        return LOG_TYPES.contains(logType) ? "logs-" + logType + "-*" : config.elasticsearchIndex;
    }

    static String detectLogType(String indexName) {
        if (indexName != null && indexName.contains("authlog")) return "authlog";
        if (indexName != null && indexName.contains("syslog")) return "syslog";
        return "unknown";
    }

    static boolean logMatchesExactFilters(LogRecord log, Filters filters) {
        if (!filters.host.isBlank() && !Objects.equals(log.host, filters.host)) return false;
        if (!filters.program.isBlank() && !Objects.equals(log.program, filters.program)) return false;
        return true;
    }

    static String formatTimestamp(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue()).atZone(JST).format(DISPLAY_TIME);
        }
        if (value instanceof String string) {
            return formatTimestampString(string);
        }
        return String.valueOf(value);
    }

    static String formatTimestampString(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        List<String> candidates = List.of(
                trimmed,
                trimmed.replace(" UTC", "Z"),
                trimmed.replace(" ", "T"),
                trimmed.replace(" UTC", "Z").replace(" ", "T")
        );
        for (String candidate : candidates) {
            try {
                return OffsetDateTime.parse(candidate).atZoneSameInstant(JST).format(DISPLAY_TIME);
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
            try {
                return LocalDateTime.parse(candidate).format(DISPLAY_TIME);
            } catch (DateTimeParseException ignored) {
                // Try next candidate.
            }
        }
        return value;
    }

    static Filters normalizeFilters(Map<String, String> values) {
        return new Filters(
                trim(values.get("time_from")),
                trim(values.get("time_to")),
                trim(values.get("log_type")),
                trim(values.get("host")),
                trim(values.get("program")),
                trim(values.get("message"))
        );
    }

    static Filters normalizeFilters(Filters filters) {
        return new Filters(
                trim(filters.timeFrom),
                trim(filters.timeTo),
                trim(filters.logType),
                trim(filters.host),
                trim(filters.program),
                trim(filters.message)
        );
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private Filters filtersFromRequest(HttpExchange exchange) throws IOException {
        Optional<String> contentType = exchange.getRequestHeaders().getFirst("Content-Type") == null
                ? Optional.empty()
                : Optional.of(exchange.getRequestHeaders().getFirst("Content-Type"));
        if (contentType.orElse("").contains("application/json")) {
            try (InputStream body = exchange.getRequestBody()) {
                byte[] bytes = body.readAllBytes();
                if (bytes.length == 0) {
                    return new Filters("", "", "", "", "", "");
                }
                return normalizeFilters(JSON.readValue(bytes, Filters.class));
            }
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            return normalizeFilters(parseForm(exchange));
        }
        return normalizeFilters(parseQuery(exchange.getRequestURI().getRawQuery()));
    }

    private Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseQuery(body);
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = urlDecode(parts[0]);
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            values.put(key, value);
        }
        return values;
    }

    static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void setSearchCookie(HttpExchange exchange, Filters filters) throws IOException {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(filters));
        exchange.getResponseHeaders().add("Set-Cookie", config.sessionCookieName + "=" + payload + "; Path=/; Max-Age=60; HttpOnly; SameSite=Lax");
    }

    private CookieSearch popSearchCookie(HttpExchange exchange) throws IOException {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return new CookieSearch(new Filters("", "", "", "", "", ""), false);
        }
        clearSearchCookie(exchange);
        String prefix = config.sessionCookieName + "=";
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                byte[] decoded = Base64.getUrlDecoder().decode(trimmed.substring(prefix.length()));
                return new CookieSearch(normalizeFilters(JSON.readValue(decoded, Filters.class)), true);
            }
        }
        return new CookieSearch(new Filters("", "", "", "", "", ""), false);
    }

    private void clearSearchCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Set-Cookie", config.sessionCookieName + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private String renderIndex(Filters filters, List<LogRecord> logs, boolean searched, String error) {
        String appTitle = "Java Elasticsearch Log Search";
        StringBuilder options = new StringBuilder();
        for (String logType : LOG_TYPES) {
            String selected = Objects.equals(filters.logType, logType) ? " selected" : "";
            options.append("<option value=\"").append(escapeHtml(logType)).append("\"").append(selected)
                    .append(">").append(escapeHtml(logType)).append("</option>");
        }

        String summary = searched
                ? "<span>" + logs.size() + " 件</span><span>最新50件のみ表示</span>"
                : "<span>検索を実施してください</span>";

        String body;
        if (!error.isBlank()) {
            body = "<p id=\"results-body\" class=\"empty\">" + escapeHtml(error) + "</p>";
        } else if (!searched) {
            body = "<p id=\"results-body\" class=\"empty\">検索条件を入力して検索ボタンを押してください。</p>";
        } else if (logs.isEmpty()) {
            body = "<p id=\"results-body\" class=\"empty\">該当するログはありません。</p>";
        } else {
            StringBuilder table = new StringBuilder();
            table.append("<div id=\"results-body\" class=\"table-wrap\"><table><thead><tr>")
                    .append("<th>Time</th><th>Log</th><th>Host</th><th>Program</th><th>Message</th>")
                    .append("</tr></thead><tbody>");
            for (LogRecord log : logs) {
                table.append("<tr><td>").append(escapeHtml(log.displayTime()))
                        .append("</td><td><span class=\"log-type log-type-").append(escapeHtml(log.logType()))
                        .append("\">").append(escapeHtml(log.logType())).append("</span></td><td>")
                        .append(escapeHtml(log.host())).append("</td><td>").append(escapeHtml(log.program()))
                        .append("</td><td>").append(escapeHtml(log.msg())).append("</td></tr>");
            }
            table.append("</tbody></table></div>");
            body = table.toString();
        }

        return indexTemplate
                .replace("{{appTitle}}", escapeHtml(appTitle))
                .replace("{{timeFrom}}", escapeHtml(filters.timeFrom))
                .replace("{{timeTo}}", escapeHtml(filters.timeTo))
                .replace("{{logTypeOptions}}", options.toString())
                .replace("{{host}}", escapeHtml(filters.host))
                .replace("{{program}}", escapeHtml(filters.program))
                .replace("{{message}}", escapeHtml(filters.message))
                .replace("{{resultsSummary}}", summary)
                .replace("{{resultsBody}}", body);
    }

    static String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        sendText(exchange, status, JSON.writeValueAsString(payload), "application/json; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    interface QueryClient {
        boolean ping();
        List<SearchHit> search(String index, Map<String, Object> query) throws Exception;
    }

    record Config(
            String port,
            String elasticsearchUrl,
            String elasticsearchIndex,
            int elasticsearchLimit,
            String staticDir,
            String sessionCookieName
    ) {
        static Config fromEnv() {
            return new Config(
                    getenv("PORT", "5000"),
                    getenv("ELASTICSEARCH_URL", "http://elastic1:9200"),
                    getenv("ELASTICSEARCH_INDEX", "logs-*"),
                    getenvInt("ELASTICSEARCH_LIMIT", 50),
                    getenv("STATIC_DIR", "static"),
                    "java_log_search_filters"
            );
        }
    }

    record Filters(
            String timeFrom,
            String timeTo,
            String logType,
            String host,
            String program,
            String message
    ) {
    }

    record LogRecord(
            String id,
            String index,
            Object eventTime,
            String displayTime,
            String logType,
            String host,
            String program,
            String msg
    ) {
    }

    record SearchHit(String id, String index, Map<String, Object> source, Object score) {
    }

    record CookieSearch(Filters filters, boolean searched) {
    }

    static class ElasticsearchClient implements QueryClient {
        private final Config config;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        private final URI baseUri;

        ElasticsearchClient(Config config) {
            this.config = config;
            this.baseUri = URI.create(config.elasticsearchUrl.replaceAll("/+$", ""));
        }

        @Override
        public boolean ping() {
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUri)
                        .timeout(java.time.Duration.ofSeconds(5))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() >= 200 && response.statusCode() < 400;
            } catch (Exception ignored) {
                return false;
            }
        }

        @Override
        public List<SearchHit> search(String index, Map<String, Object> query) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("sort", List.of(Map.of("@timestamp", Map.of("order", "desc", "unmapped_type", "date"))));
            body.put("size", config.elasticsearchLimit);
            body.put("track_total_hits", false);
            body.put("timeout", "5s");
            body.put("_source", List.of("@timestamp", "host", "program", "msg", "severity", "dt", "hr"));

            URI searchUri = URI.create(baseUri + "/" + index.replaceAll("^/+|/+$", "") + "/_search?ignore_unavailable=true");
            HttpRequest request = HttpRequest.newBuilder(searchUri)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("elasticsearch search failed: HTTP " + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> decoded = JSON.readValue(response.body(), new TypeReference<>() {});
            if (decoded.containsKey("error")) {
                throw new IOException("elasticsearch search failed: " + decoded.get("error"));
            }
            List<SearchHit> results = new ArrayList<>();
            Object hitsObject = decoded.get("hits");
            if (!(hitsObject instanceof Map<?, ?> hitsMap) || !(hitsMap.get("hits") instanceof List<?> hitRows)) {
                return results;
            }
            for (Object hitRow : hitRows) {
                Map<?, ?> hit = (Map<?, ?>) hitRow;
                Map<String, Object> source = hit.get("_source") instanceof Map<?, ?> sourceMap
                        ? new LinkedHashMap<>((Map<String, Object>) sourceMap)
                        : new LinkedHashMap<>();
                results.add(new SearchHit(
                        stringValue(hit.get("_id")),
                        stringValue(hit.get("_index")),
                        source,
                        hit.get("_score")
                ));
            }
            return results;
        }
    }

    static class StaticHandler implements HttpHandler {
        private final Path staticDir;

        StaticHandler(Path staticDir) {
            this.staticDir = staticDir;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String rawPath = exchange.getRequestURI().getPath().replaceFirst("^/static/?", "");
            Path file = staticDir.resolve(rawPath).normalize();
            if (!file.startsWith(staticDir.normalize()) || !Files.isRegularFile(file)) {
                byte[] notFound = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(notFound);
                }
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            if (file.toString().endsWith(".css")) {
                headers.set("Content-Type", "text/css; charset=utf-8");
            } else if (file.toString().endsWith(".js")) {
                headers.set("Content-Type", "application/javascript; charset=utf-8");
            }
            byte[] bytes = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    static String getenv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static int getenvInt(String key, int fallback) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(key, ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
