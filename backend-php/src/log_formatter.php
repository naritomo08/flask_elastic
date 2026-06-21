<?php

declare(strict_types=1);

function format_timestamp(mixed $value): string
{
    if ($value === null || $value === '') {
        return '';
    }
    $jst = new DateTimeZone(JST_TIMEZONE);
    if (is_int($value) || is_float($value)) {
        $date = DateTimeImmutable::createFromFormat('U.u', sprintf('%.6F', ((float) $value) / 1000), new DateTimeZone('UTC'));
        return $date ? $date->setTimezone($jst)->format('Y/m/d H:i:s') . ' JST' : (string) $value;
    }
    if (is_string($value)) {
        $trimmed = trim($value);
        $normalized = str_replace([' UTC', ' '], ['Z', 'T'], $trimmed);
        try {
            $date = new DateTimeImmutable(str_replace('Z', '+00:00', $normalized));
            return preg_match('/(?:Z|[+-]\d{2}:?\d{2})$/', $trimmed)
                ? $date->setTimezone($jst)->format('Y/m/d H:i:s') . ' JST'
                : $date->format('Y/m/d H:i:s') . ' JST';
        } catch (Exception) {
            return $trimmed;
        }
    }
    return (string) $value;
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
    if ($filters['host'] !== '' && regex_pattern($filters['host']) === null && ($log['host'] ?? '') !== $filters['host']) {
        return false;
    }
    if ($filters['program'] !== '' && regex_pattern($filters['program']) === null && ($log['program'] ?? '') !== $filters['program']) {
        return false;
    }
    return true;
}
