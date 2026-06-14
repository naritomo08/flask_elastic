<?php

declare(strict_types=1);

require_once __DIR__ . '/config.php';

function format_timestamp(mixed $value): string
{
    if ($value === null || $value === '') {
        return '';
    }

    $jst = new DateTimeZone(JST_TIMEZONE);
    if (is_int($value) || is_float($value)) {
        $seconds = ((float) $value) / 1000;
        $date = DateTimeImmutable::createFromFormat('U.u', sprintf('%.6F', $seconds), new DateTimeZone('UTC'));
        return $date ? $date->setTimezone($jst)->format('Y/m/d H:i:s') . ' JST' : (string) $value;
    }

    if (is_string($value)) {
        $trimmed = trim($value);
        $normalized = str_replace([' UTC', ' '], ['Z', 'T'], $trimmed);
        $normalized = str_replace('Z', '+00:00', $normalized);
        try {
            $date = new DateTimeImmutable($normalized);
            if (!preg_match('/(?:Z|[+-]\d{2}:?\d{2})$/', $trimmed)) {
                return $date->format('Y/m/d H:i:s') . ' JST';
            }
            return $date->setTimezone($jst)->format('Y/m/d H:i:s') . ' JST';
        } catch (Exception) {
            return $trimmed;
        }
    }

    return (string) $value;
}

function datetime_local_to_iso(string $value): string
{
    if ($value === '') {
        return '';
    }

    try {
        $date = new DateTimeImmutable($value, new DateTimeZone(JST_TIMEZONE));
        return $date->setTimezone(new DateTimeZone('UTC'))->format(DATE_ATOM);
    } catch (Exception) {
        return $value;
    }
}

function wildcard_value(string $value): string
{
    return '*' . str_replace(['\\', '*', '?'], ['\\\\', '\\*', '\\?'], $value) . '*';
}

function text_search_clause(string $field, string $value): array
{
    return [
        'bool' => [
            'should' => [
                ['match_phrase' => [$field => ['query' => $value]]],
                ['match' => [$field => ['query' => $value, 'operator' => 'and']]],
                ['wildcard' => [$field => ['value' => wildcard_value($value), 'case_insensitive' => true]]],
                ['wildcard' => ["{$field}.keyword" => ['value' => wildcard_value($value), 'case_insensitive' => true]]],
            ],
            'minimum_should_match' => 1,
        ],
    ];
}

function exact_match_clause(string $field, string $value): array
{
    return [
        'bool' => [
            'should' => [
                ['term' => ["{$field}.keyword" => ['value' => $value]]],
                ['term' => [$field => ['value' => $value]]],
            ],
            'minimum_should_match' => 1,
        ],
    ];
}

function build_query(array $filters, array $config): array
{
    $must = [];
    $filter = [];

    if ($filters['message'] !== '') {
        $must[] = text_search_clause('msg', $filters['message']);
    }
    if ($filters['host'] !== '') {
        $filter[] = exact_match_clause('host', $filters['host']);
    }
    if ($filters['program'] !== '') {
        $filter[] = exact_match_clause('program', $filters['program']);
    }

    $timeRange = [];
    if ($filters['time_from'] !== '') {
        $timeRange['gte'] = datetime_local_to_iso($filters['time_from']);
    }
    if ($filters['time_to'] !== '') {
        $timeRange['lte'] = datetime_local_to_iso($filters['time_to']);
    }
    if ($timeRange) {
        $filter[] = ['range' => ['@timestamp' => $timeRange]];
    }

    if (!$must && !$filter) {
        return ['match_all' => new stdClass()];
    }

    $bool = [];
    if ($must) {
        $bool['must'] = $must;
    }
    if ($filter) {
        $bool['filter'] = $filter;
    }
    return ['bool' => $bool];
}

function index_pattern_for_log_type(string $logType, array $config): string
{
    return in_array($logType, LOG_TYPES, true) ? "logs-{$logType}-*" : $config['elasticsearch_index'];
}

function detect_log_type(string $indexName): string
{
    if (str_contains($indexName, 'authlog')) {
        return 'authlog';
    }
    if (str_contains($indexName, 'syslog')) {
        return 'syslog';
    }
    return 'unknown';
}

function log_matches_exact_filters(array $log, array $filters): bool
{
    if ($filters['host'] !== '' && ($log['host'] ?? '') !== $filters['host']) {
        return false;
    }
    if ($filters['program'] !== '' && ($log['program'] ?? '') !== $filters['program']) {
        return false;
    }
    return true;
}

function elasticsearch_request(string $method, string $url, ?array $body = null, int $timeout = 15): array
{
    $curl = curl_init($url);
    $headers = ['Content-Type: application/json'];
    $options = [
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_HTTPHEADER => $headers,
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

function elasticsearch_search(array $filters, array $config): array
{
    $index = index_pattern_for_log_type($filters['log_type'], $config);
    $url = rtrim($config['elasticsearch_url'], '/') . '/' . trim($index, '/') . '/_search?ignore_unavailable=true';
    $body = [
        'query' => build_query($filters, $config),
        'sort' => [['@timestamp' => ['order' => 'desc', 'unmapped_type' => 'date']]],
        'size' => $config['default_limit'],
        'track_total_hits' => false,
        'timeout' => '5s',
        '_source' => ['@timestamp', 'host', 'program', 'msg', 'severity', 'dt', 'hr'],
    ];

    $response = elasticsearch_request('POST', $url, $body);
    return $response['hits']['hits'] ?? [];
}

function search_logs(array $filters, array $config): array
{
    $hits = elasticsearch_search($filters, $config);
    $logs = [];
    foreach ($hits as $hit) {
        $source = is_array($hit['_source'] ?? null) ? $hit['_source'] : [];
        $log = array_merge($source, [
            'id' => $hit['_id'] ?? '',
            'index' => $hit['_index'] ?? '',
            'log_type' => detect_log_type((string) ($hit['_index'] ?? '')),
            'display_time' => format_timestamp($source['@timestamp'] ?? null),
            'score' => $hit['_score'] ?? null,
        ]);
        if (log_matches_exact_filters($log, $filters)) {
            $logs[] = $log;
        }
    }
    return $logs;
}
