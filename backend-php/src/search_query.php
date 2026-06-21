<?php

declare(strict_types=1);

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
    return ['bool' => [
        'should' => [
            ['match_phrase' => [$field => ['query' => $value]]],
            ['match' => [$field => ['query' => $value, 'operator' => 'and']]],
            ['wildcard' => [$field => ['value' => wildcard_value($value), 'case_insensitive' => true]]],
            ['wildcard' => ["{$field}.keyword" => ['value' => wildcard_value($value), 'case_insensitive' => true]]],
        ],
        'minimum_should_match' => 1,
    ]];
}

function exact_match_clause(string $field, string $value): array
{
    return ['bool' => [
        'should' => [
            ['term' => ["{$field}.keyword" => ['value' => $value]]],
            ['term' => [$field => ['value' => $value]]],
        ],
        'minimum_should_match' => 1,
    ]];
}

function regex_pattern(string $value): ?string
{
    return strlen($value) >= 2 && str_starts_with($value, '/') && str_ends_with($value, '/')
        ? substr($value, 1, -1)
        : null;
}

function exact_or_regex_clause(string $field, string $value): array
{
    $pattern = regex_pattern($value);
    if ($pattern === null) {
        return exact_match_clause($field, $value);
    }
    return ['bool' => [
        'should' => [
            ['regexp' => ["{$field}.keyword" => ['value' => $pattern, 'case_insensitive' => true]]],
            ['regexp' => [$field => ['value' => $pattern, 'case_insensitive' => true]]],
        ],
        'minimum_should_match' => 1,
    ]];
}

function build_query(array $filters, array $config = []): array
{
    $must = [];
    $filter = [];
    if ($filters['message'] !== '') {
        $must[] = text_search_clause('msg', $filters['message']);
    }
    if ($filters['host'] !== '') {
        $filter[] = exact_or_regex_clause('host', $filters['host']);
    }
    if ($filters['program'] !== '') {
        $filter[] = exact_or_regex_clause('program', $filters['program']);
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
