<?php

declare(strict_types=1);

const LOG_TYPES = ['syslog', 'authlog'];
const JST_TIMEZONE = 'Asia/Tokyo';

function app_config(): array
{
    return [
        'elasticsearch_url' => getenv('ELASTICSEARCH_URL') ?: 'http://elastic1:9200',
        'elasticsearch_index' => getenv('ELASTICSEARCH_INDEX') ?: 'logs-*',
        'default_limit' => (int) (getenv('ELASTICSEARCH_LIMIT') ?: '50'),
    ];
}
