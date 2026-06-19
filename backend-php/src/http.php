<?php

declare(strict_types=1);

use Psr\Http\Message\ResponseInterface as Response;
use Psr\Http\Message\ServerRequestInterface as Request;
use Slim\Factory\AppFactory;

function normalize_filters(array $args): array
{
    return [
        'time_from' => trim((string) ($args['time_from'] ?? '')),
        'time_to' => trim((string) ($args['time_to'] ?? '')),
        'log_type' => trim((string) ($args['log_type'] ?? '')),
        'host' => trim((string) ($args['host'] ?? '')),
        'program' => trim((string) ($args['program'] ?? '')),
        'message' => trim((string) ($args['message'] ?? '')),
    ];
}

function filters_from_request(Request $request): array
{
    $contentType = $request->getHeaderLine('Content-Type');
    if (str_contains($contentType, 'application/json')) {
        $payload = $request->getParsedBody();
        if ($payload === null) {
            $payload = json_decode((string) $request->getBody(), true);
        }
        return normalize_filters(is_array($payload) ? $payload : []);
    }

    if ($request->getMethod() === 'POST') {
        $payload = $request->getParsedBody();
        return normalize_filters(is_array($payload) ? $payload : []);
    }

    return normalize_filters($request->getQueryParams());
}

function positive_int(mixed $value, int $default, ?int $maximum = null): int
{
    $parsed = filter_var($value, FILTER_VALIDATE_INT);
    $parsed = $parsed !== false && $parsed > 0 ? $parsed : $default;
    return $maximum === null ? $parsed : min($parsed, $maximum);
}

function json_response(Response $response, array $payload, int $status = 200): Response
{
    $response->getBody()->write(json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    return $response
        ->withHeader('Content-Type', 'application/json; charset=utf-8')
        ->withStatus($status);
}

function create_app(): \Slim\App
{
    $app = AppFactory::create();
    $app->addBodyParsingMiddleware();
    $app->addRoutingMiddleware();

    $config = app_config();

    $app->get('/', function (Request $request, Response $response): Response {
        return json_response($response, [
            'service' => 'php-elastic-backend',
            'endpoints' => ['/health', '/api/options', '/api/logs'],
        ]);
    });

    $app->get('/health', function (Request $request, Response $response) use ($config): Response {
        return json_response($response, elasticsearch_health($config));
    });

    $app->get('/api/options', function (Request $request, Response $response): Response {
        return json_response($response, ['log_types' => LOG_TYPES]);
    });

    $app->map(['GET', 'POST'], '/api/logs', function (Request $request, Response $response) use ($config): Response {
        $filters = filters_from_request($request);
        $params = array_merge($request->getQueryParams(), is_array($request->getParsedBody()) ? $request->getParsedBody() : []);
        $page = positive_int($params['page'] ?? null, 1);
        $size = positive_int($params['size'] ?? null, 20, 100);
        try {
            $result = search_logs($filters, $config, $page, $size);
            return json_response($response, array_merge([
                'filters' => $filters,
                'count' => count($result['results']),
                'logs' => $result['results'],
            ], $result));
        } catch (Throwable $error) {
            return json_response($response, ['error' => $error->getMessage()], 502);
        }
    });

    $app->addErrorMiddleware(true, true, true);

    return $app;
}
