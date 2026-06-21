package com.example.flaskelastic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

public class App {
    static final List<String> LOG_TYPES = List.of("syslog", "authlog");
    static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Config config;
    private final QueryClient queryClient;
    private final Clock clock;

    public App(Config config, QueryClient queryClient, Clock clock) {
        this.config = config;
        this.queryClient = queryClient;
        this.clock = clock;
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnv();
        new App(config, new ElasticsearchClient(config), Clock.systemUTC()).start();
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(config.port())), 0);
        server.createContext("/", this::handleApiRoot);
        server.createContext("/health", this::handleHealth);
        server.createContext("/api/options", this::handleApiOptions);
        server.createContext("/api/logs", this::handleApiLogs);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.printf("listening on :%s%n", config.port());
    }

    private void handleApiRoot(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, Map.of(
                "service", "java-elastic-backend",
                "endpoints", List.of("/health", "/api/options", "/api/logs")
        ));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        long startedAt = System.nanoTime();
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            Map<String, Object> info = queryClient.info();
            payload.put("ok", true);
            payload.put("status", "ok");
            payload.put("cluster_name", Values.string(info.get("cluster_name")));
            if (info.get("version") instanceof Map<?, ?> version) {
                payload.put("version", Values.string(version.get("number")));
            }
        } catch (Exception error) {
            payload.put("ok", false);
            payload.put("status", "error");
            payload.put("error", error.getMessage());
        }
        payload.put("backend", "java");
        payload.put("elasticsearch_url", config.elasticsearchUrl());
        payload.put("index", config.elasticsearchIndex());
        payload.put("latency_ms", (System.nanoTime() - startedAt) / 1_000_000);
        sendJson(exchange, 200, payload);
    }

    private void handleApiOptions(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, Map.of("log_types", LOG_TYPES));
    }

    private void handleApiLogs(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            sendText(exchange, 405, "method not allowed", "text/plain; charset=utf-8");
            return;
        }
        try {
            Filters filters = filtersFromRequest(exchange);
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            int page = Values.positiveInt(params.get("page"), 1, null);
            int size = Values.positiveInt(params.get("size"), 20, 100);
            LogSearchResult result = LogService.search(queryClient, config, filters, page, size, clock);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("filters", filters);
            payload.put("total", result.total());
            payload.put("page", page);
            payload.put("size", size);
            payload.put("results", result.logs());
            payload.put("count", result.logs().size());
            payload.put("logs", result.logs());
            sendJson(exchange, 200, payload);
        } catch (Exception error) {
            sendJson(exchange, 502, Map.of("error", error.getMessage()));
        }
    }

    private Filters filtersFromRequest(HttpExchange exchange) throws IOException {
        Optional<String> contentType = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type"));
        if (contentType.orElse("").contains("application/json")) {
            try (InputStream body = exchange.getRequestBody()) {
                byte[] bytes = body.readAllBytes();
                return bytes.length == 0 ? Filters.empty() : Filters.normalize(JSON.readValue(bytes, Filters.class));
            }
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            return Filters.normalize(parseQuery(body));
        }
        return Filters.normalize(parseQuery(exchange.getRequestURI().getRawQuery()));
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            values.put(urlDecode(parts[0]), parts.length > 1 ? urlDecode(parts[1]) : "");
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
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
}
