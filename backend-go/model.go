package main

import (
	"context"
	"time"
)

type App struct {
	client ElasticSearcher
}

type ElasticSearcher interface {
	Ping(ctx context.Context) bool
	Info(ctx context.Context) (map[string]any, error)
	Search(ctx context.Context, index string, query map[string]any, page int, size int, timeout time.Duration) (ElasticSearchResult, error)
}

type Filters struct {
	TimeFrom string `json:"time_from"`
	TimeTo   string `json:"time_to"`
	LogType  string `json:"log_type"`
	Host     string `json:"host"`
	Program  string `json:"program"`
	Message  string `json:"message"`
}

type LogRecord map[string]any

type LogSearchResult struct {
	Total int
	Logs  []LogRecord
}

type ElasticHit struct {
	ID     string         `json:"_id"`
	Index  string         `json:"_index"`
	Score  any            `json:"_score"`
	Source map[string]any `json:"_source"`
}

type ElasticSearchResult struct {
	Total int
	Hits  []ElasticHit
}

type elasticResponse struct {
	Hits struct {
		Total any          `json:"total"`
		Hits  []ElasticHit `json:"hits"`
	} `json:"hits"`
	Error any `json:"error"`
}
