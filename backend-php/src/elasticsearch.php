<?php

declare(strict_types=1);

function elasticsearch_request(string $method, string $url, ?array $body = null, int $timeout = 15): array
{
    $curl = curl_init($url);
    $options = [
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => $timeout,
    ];
    if ($body !== null) {
        $options[CURLOPT_POSTFIELDS] = json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }
    if ($method === 'HEAD') {
        $options[CURLOPT_NOBODY] = true;
    }
    curl_setopt_array($curl, $options);
    $response = curl_exec($curl);
    $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
    $error = curl_error($curl);
    curl_close($curl);

    if ($response === false || $status >= 400) {
        throw new RuntimeException($error !== '' ? $error : "Elasticsearch request failed with status {$status}: {$response}");
    }
    if ($method === 'HEAD') {
        return [];
    }
    $decoded = json_decode((string) $response, true);
    if (!is_array($decoded)) {
        throw new RuntimeException('Elasticsearch returned invalid JSON');
    }
    if (isset($decoded['error'])) {
        $message = is_array($decoded['error']) ? json_encode($decoded['error'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : (string) $decoded['error'];
        throw new RuntimeException("Elasticsearch search failed: {$message}");
    }
    return $decoded;
}

function elasticsearch_ping(array $config): bool
{
    try {
        elasticsearch_request('HEAD', rtrim($config['elasticsearch_url'], '/'), null, 3);
        return true;
    } catch (Throwable) {
        return false;
    }
}

function elasticsearch_health(array $config): array
{
    $started = microtime(true);
    try {
        $info = elasticsearch_request('GET', rtrim($config['elasticsearch_url'], '/'), null, 3);
        return [
            'ok' => true, 'status' => 'ok', 'backend' => 'php',
            'elasticsearch_url' => $config['elasticsearch_url'], 'index' => $config['elasticsearch_index'],
            'latency_ms' => (int) round((microtime(true) - $started) * 1000),
            'cluster_name' => (string) ($info['cluster_name'] ?? ''),
            'version' => (string) ($info['version']['number'] ?? ''),
        ];
    } catch (Throwable $error) {
        return [
            'ok' => false, 'status' => 'error', 'backend' => 'php',
            'elasticsearch_url' => $config['elasticsearch_url'], 'index' => $config['elasticsearch_index'],
            'latency_ms' => (int) round((microtime(true) - $started) * 1000),
            'error' => $error->getMessage(),
        ];
    }
}

function elasticsearch_search(array $filters, array $config, int $page, int $size): array
{
    $index = index_pattern_for_log_type($filters['log_type'], $config);
    $url = rtrim($config['elasticsearch_url'], '/') . '/' . trim($index, '/') . '/_search?ignore_unavailable=true';
    $body = [
        'query' => build_query($filters, $config),
        'sort' => [['@timestamp' => ['order' => 'desc', 'unmapped_type' => 'date']]],
        'from' => ($page - 1) * $size, 'size' => $size, 'track_total_hits' => true, 'timeout' => '5s',
        '_source' => ['@timestamp', 'host', 'program', 'msg', 'severity', 'dt', 'hr'],
    ];
    $response = elasticsearch_request('POST', $url, $body);
    $totalValue = $response['hits']['total'] ?? 0;
    return [
        'total' => is_array($totalValue) ? (int) ($totalValue['value'] ?? 0) : (int) $totalValue,
        'hits' => $response['hits']['hits'] ?? [],
    ];
}

function search_logs(array $filters, array $config, int $page = 1, int $size = 20): array
{
    $search = elasticsearch_search($filters, $config, $page, $size);
    $logs = [];
    foreach ($search['hits'] as $hit) {
        $source = is_array($hit['_source'] ?? null) ? $hit['_source'] : [];
        $log = array_merge($source, [
            'id' => $hit['_id'] ?? '', 'index' => $hit['_index'] ?? '',
            'log_type' => detect_log_type((string) ($hit['_index'] ?? '')),
            'display_time' => format_timestamp($source['@timestamp'] ?? null),
            'score' => $hit['_score'] ?? null,
        ]);
        if (log_matches_exact_filters($log, $filters)) {
            $logs[] = $log;
        }
    }
    return ['total' => $search['total'], 'page' => $page, 'size' => $size, 'results' => $logs];
}
