package com.example.flaskelastic;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ElasticsearchClient implements QueryClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI baseUri;

    ElasticsearchClient(Config config) {
        this.baseUri = URI.create(config.elasticsearchUrl().replaceAll("/+$", ""));
    }

    @Override
    public Map<String, Object> info() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri).timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new IOException("elasticsearch info failed: HTTP " + response.statusCode());
        }
        return App.JSON.readValue(response.body(), new TypeReference<>() {});
    }

    @Override
    public ElasticSearchResult search(String index, Map<String, Object> query, int page, int size) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("sort", List.of(Map.of("@timestamp", Map.of("order", "desc", "unmapped_type", "date"))));
        body.put("from", (page - 1) * size);
        body.put("size", size);
        body.put("track_total_hits", true);
        body.put("timeout", "5s");
        body.put("_source", List.of("@timestamp", "host", "program", "msg", "severity", "dt", "hr"));

        URI searchUri = URI.create(baseUri + "/" + index.replaceAll("^/+|/+$", "") + "/_search?ignore_unavailable=true");
        HttpRequest request = HttpRequest.newBuilder(searchUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(App.JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("elasticsearch search failed: HTTP " + response.statusCode() + ": " + response.body());
        }
        Map<String, Object> decoded = App.JSON.readValue(response.body(), new TypeReference<>() {});
        if (decoded.containsKey("error")) {
            throw new IOException("elasticsearch search failed: " + decoded.get("error"));
        }
        return decodeResult(decoded);
    }

    @SuppressWarnings("unchecked")
    private ElasticSearchResult decodeResult(Map<String, Object> decoded) {
        List<SearchHit> results = new ArrayList<>();
        if (!(decoded.get("hits") instanceof Map<?, ?> hitsMap)
                || !(hitsMap.get("hits") instanceof List<?> hitRows)) {
            return new ElasticSearchResult(0, results);
        }
        Object totalValue = hitsMap.get("total");
        int total = totalValue instanceof Map<?, ?> totalMap
                ? Integer.parseInt(Values.string(totalMap.get("value")))
                : Integer.parseInt(Values.string(totalValue));
        for (Object hitRow : hitRows) {
            Map<?, ?> hit = (Map<?, ?>) hitRow;
            Map<String, Object> source = hit.get("_source") instanceof Map<?, ?> sourceMap
                    ? new LinkedHashMap<>((Map<String, Object>) sourceMap) : new LinkedHashMap<>();
            results.add(new SearchHit(
                    Values.string(hit.get("_id")), Values.string(hit.get("_index")), source, hit.get("_score")
            ));
        }
        return new ElasticSearchResult(total, results);
    }
}
