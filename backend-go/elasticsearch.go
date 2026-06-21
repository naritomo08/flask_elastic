package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type ElasticClient struct {
	baseURL    string
	httpClient *http.Client
}

func NewElasticClient(baseURL string) *ElasticClient {
	return &ElasticClient{
		baseURL:    strings.TrimRight(baseURL, "/"),
		httpClient: &http.Client{Timeout: 20 * time.Second},
	}
}

func (c *ElasticClient) Ping(ctx context.Context) bool {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, c.baseURL, nil)
	if err != nil {
		return false
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode >= 200 && resp.StatusCode < 400
}

func (c *ElasticClient) Info(ctx context.Context) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return nil, fmt.Errorf("elasticsearch info failed: %s", resp.Status)
	}
	var payload map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	return payload, nil
}

func (c *ElasticClient) Search(ctx context.Context, index string, query map[string]any, page, size int, timeout time.Duration) (ElasticSearchResult, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	body := map[string]any{
		"query": query,
		"sort":  []any{map[string]any{"@timestamp": map[string]any{"order": "desc", "unmapped_type": "date"}}},
		"from":  (page - 1) * size, "size": size, "track_total_hits": true, "timeout": "5s",
		"_source": []string{"@timestamp", "host", "program", "msg", "severity", "dt", "hr"},
	}
	var payload bytes.Buffer
	if err := json.NewEncoder(&payload).Encode(body); err != nil {
		return ElasticSearchResult{}, err
	}
	searchURL := c.baseURL + "/" + strings.Trim(index, "/") + "/_search?ignore_unavailable=true"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, searchURL, &payload)
	if err != nil {
		return ElasticSearchResult{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return ElasticSearchResult{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		responseBody, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return ElasticSearchResult{}, fmt.Errorf("elasticsearch search failed: %s: %s", resp.Status, strings.TrimSpace(string(responseBody)))
	}
	var decoded elasticResponse
	decoder := json.NewDecoder(resp.Body)
	decoder.UseNumber()
	if err := decoder.Decode(&decoded); err != nil {
		return ElasticSearchResult{}, err
	}
	if decoded.Error != nil {
		return ElasticSearchResult{}, fmt.Errorf("elasticsearch search failed: %v", decoded.Error)
	}
	return ElasticSearchResult{Total: totalHits(decoded.Hits.Total), Hits: decoded.Hits.Hits}, nil
}
