package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	elasticsearchURL   = getenv("ELASTICSEARCH_URL", "http://elastic1:9200")
	elasticsearchIndex = getenv("ELASTICSEARCH_INDEX", "logs-*")
	defaultLimit       = getenvInt("ELASTICSEARCH_LIMIT", 50)
	jst                = time.FixedZone("JST", 9*60*60)
	logTypes           = []string{"syslog", "authlog"}
)

type App struct {
	client   ElasticSearcher
	template *template.Template
	sessions *SessionStore
}

type ElasticSearcher interface {
	Ping(ctx context.Context) bool
	Search(ctx context.Context, index string, query map[string]any, timeout time.Duration) ([]ElasticHit, error)
}

type ElasticClient struct {
	baseURL    string
	httpClient *http.Client
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

type PageData struct {
	Filters  Filters
	Logs     []LogRecord
	LogTypes []string
	Searched bool
}

type PendingSearch struct {
	Filters Filters
}

type SessionStore struct {
	mu    sync.Mutex
	items map[string]PendingSearch
}

type ElasticHit struct {
	ID     string         `json:"_id"`
	Index  string         `json:"_index"`
	Score  any            `json:"_score"`
	Source map[string]any `json:"_source"`
}

type elasticResponse struct {
	Hits struct {
		Hits []ElasticHit `json:"hits"`
	} `json:"hits"`
	Error any `json:"error"`
}

func main() {
	app, err := NewApp(NewElasticClient(elasticsearchURL))
	if err != nil {
		log.Fatal(err)
	}

	server := &http.Server{
		Addr:              ":5000",
		Handler:           app.routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	log.Printf("listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}

func NewApp(client ElasticSearcher) (*App, error) {
	return &App{
		client:   client,
		template: nil,
		sessions: &SessionStore{
			items: map[string]PendingSearch{},
		},
	}, nil
}

func (a *App) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.apiRoot)
	mux.HandleFunc("/health", a.health)
	mux.HandleFunc("/api/options", a.apiOptions)
	mux.HandleFunc("/api/logs", a.apiSearchLogs)
	return mux
}

func (a *App) apiRoot(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{
		"service":   "go-elastic-backend",
		"endpoints": []string{"/health", "/api/options", "/api/logs"},
	})
}

func NewElasticClient(baseURL string) *ElasticClient {
	return &ElasticClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		httpClient: &http.Client{
			Timeout: 20 * time.Second,
		},
	}
}

func (c *ElasticClient) Ping(ctx context.Context) bool {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
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

func (c *ElasticClient) Search(ctx context.Context, index string, query map[string]any, timeout time.Duration) ([]ElasticHit, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	body := map[string]any{
		"query": query,
		"sort": []any{
			map[string]any{
				"@timestamp": map[string]any{
					"order":         "desc",
					"unmapped_type": "date",
				},
			},
		},
		"size":             defaultLimit,
		"track_total_hits": false,
		"timeout":          "5s",
		"_source":          []string{"@timestamp", "host", "program", "msg", "severity", "dt", "hr"},
	}
	var payload bytes.Buffer
	if err := json.NewEncoder(&payload).Encode(body); err != nil {
		return nil, err
	}

	searchURL := c.baseURL + "/" + strings.Trim(index, "/") + "/_search?ignore_unavailable=true"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, searchURL, &payload)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return nil, fmt.Errorf("elasticsearch search failed: %s: %s", resp.Status, strings.TrimSpace(string(body)))
	}

	var decoded elasticResponse
	decoder := json.NewDecoder(resp.Body)
	decoder.UseNumber()
	if err := decoder.Decode(&decoded); err != nil {
		return nil, err
	}
	if decoded.Error != nil {
		return nil, fmt.Errorf("elasticsearch search failed: %v", decoded.Error)
	}
	return decoded.Hits.Hits, nil
}

func (a *App) index(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
	case http.MethodPost:
		filters, err := filtersFromRequest(r)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		a.sessions.Save(w, r, filters)
		http.Redirect(w, r, "/", http.StatusFound)
		return
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var filters Filters
	searched := false
	if r.URL.RawQuery != "" {
		filters = filtersFromValues(r.URL.Query())
		searched = true
	} else if pending, ok := a.sessions.Pop(w, r); ok {
		filters = pending.Filters
		searched = true
	} else {
		filters = normalizeFilters(Filters{})
	}

	var logs []LogRecord
	if searched {
		var err error
		logs, err = searchLogs(r.Context(), a.client, filters)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
	}

	data := PageData{
		Filters:  filters,
		Logs:     logs,
		LogTypes: logTypes,
		Searched: searched,
	}
	var rendered bytes.Buffer
	if err := a.template.Execute(&rendered, data); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(rendered.Bytes())
}

func (a *App) clearFilters(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	a.sessions.Clear(w, r)
	http.Redirect(w, r, "/", http.StatusFound)
}

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, map[string]any{
		"ok":                a.client.Ping(r.Context()),
		"elasticsearch_url": elasticsearchURL,
		"index":             elasticsearchIndex,
	})
}

func (a *App) apiOptions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, map[string]any{
		"log_types": logTypes,
	})
}

func (a *App) apiSearchLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	filters, err := filtersFromRequest(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	logs, err := searchLogs(r.Context(), a.client, filters)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	writeJSON(w, map[string]any{
		"filters": filters,
		"count":   len(logs),
		"logs":    logs,
	})
}

func filtersFromRequest(r *http.Request) (Filters, error) {
	contentType := r.Header.Get("Content-Type")
	if strings.HasPrefix(contentType, "application/json") {
		var filters Filters
		if r.Body != nil {
			if err := json.NewDecoder(r.Body).Decode(&filters); err != nil && !errors.Is(err, io.EOF) {
				return Filters{}, err
			}
		}
		return normalizeFilters(filters), nil
	}

	if r.Method == http.MethodPost {
		if err := r.ParseForm(); err != nil {
			return Filters{}, err
		}
		return filtersFromValues(r.PostForm), nil
	}

	return filtersFromValues(r.URL.Query()), nil
}

func filtersFromValues(values url.Values) Filters {
	return normalizeFilters(Filters{
		TimeFrom: values.Get("time_from"),
		TimeTo:   values.Get("time_to"),
		LogType:  values.Get("log_type"),
		Host:     values.Get("host"),
		Program:  values.Get("program"),
		Message:  values.Get("message"),
	})
}

func normalizeFilters(filters Filters) Filters {
	return Filters{
		TimeFrom: strings.TrimSpace(filters.TimeFrom),
		TimeTo:   strings.TrimSpace(filters.TimeTo),
		LogType:  strings.TrimSpace(filters.LogType),
		Host:     strings.TrimSpace(filters.Host),
		Program:  strings.TrimSpace(filters.Program),
		Message:  strings.TrimSpace(filters.Message),
	}
}

func searchLogs(ctx context.Context, client ElasticSearcher, filters Filters) ([]LogRecord, error) {
	query := buildQuery(filters)
	hits, err := client.Search(ctx, indexPatternForLogType(filters.LogType), query, 15*time.Second)
	if err != nil {
		return nil, err
	}

	logs := make([]LogRecord, 0, len(hits))
	for _, hit := range hits {
		logRecord := LogRecord{
			"id":       hit.ID,
			"index":    hit.Index,
			"log_type": detectLogType(hit.Index),
			"score":    hit.Score,
		}
		for key, value := range hit.Source {
			logRecord[key] = value
		}
		logRecord["display_time"] = formatTimestamp(logRecord["@timestamp"])
		if logMatchesExactFilters(logRecord, filters) {
			logs = append(logs, logRecord)
		}
	}
	return logs, nil
}

func buildQuery(filters Filters) map[string]any {
	must := make([]any, 0, 1)
	filterClauses := make([]any, 0, 3)

	if filters.Message != "" {
		must = append(must, textSearchClause("msg", filters.Message))
	}
	if filters.Program != "" {
		filterClauses = append(filterClauses, exactMatchClause("program", filters.Program))
	}
	if filters.Host != "" {
		filterClauses = append(filterClauses, exactMatchClause("host", filters.Host))
	}

	timeRange := map[string]any{}
	if filters.TimeFrom != "" {
		timeRange["gte"] = datetimeLocalToISO(filters.TimeFrom)
	}
	if filters.TimeTo != "" {
		timeRange["lte"] = datetimeLocalToISO(filters.TimeTo)
	}
	if len(timeRange) > 0 {
		filterClauses = append(filterClauses, map[string]any{
			"range": map[string]any{
				"@timestamp": timeRange,
			},
		})
	}

	if len(must) == 0 && len(filterClauses) == 0 {
		return map[string]any{"match_all": map[string]any{}}
	}

	boolQuery := map[string]any{}
	if len(must) > 0 {
		boolQuery["must"] = must
	}
	if len(filterClauses) > 0 {
		boolQuery["filter"] = filterClauses
	}
	return map[string]any{"bool": boolQuery}
}

func textSearchClause(field, value string) map[string]any {
	return map[string]any{
		"bool": map[string]any{
			"should": []any{
				map[string]any{"match_phrase": map[string]any{field: map[string]any{"query": value}}},
				map[string]any{"match": map[string]any{field: map[string]any{"query": value, "operator": "and"}}},
				map[string]any{"wildcard": map[string]any{field: map[string]any{"value": wildcardValue(value), "case_insensitive": true}}},
				map[string]any{"wildcard": map[string]any{field + ".keyword": map[string]any{"value": wildcardValue(value), "case_insensitive": true}}},
			},
			"minimum_should_match": 1,
		},
	}
}

func exactMatchClause(field, value string) map[string]any {
	return map[string]any{
		"bool": map[string]any{
			"should": []any{
				map[string]any{"term": map[string]any{field + ".keyword": map[string]any{"value": value}}},
				map[string]any{"term": map[string]any{field: map[string]any{"value": value}}},
			},
			"minimum_should_match": 1,
		},
	}
}

func wildcardValue(value string) string {
	escaped := strings.ReplaceAll(value, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `*`, `\*`)
	escaped = strings.ReplaceAll(escaped, `?`, `\?`)
	return "*" + escaped + "*"
}

func detectLogType(indexName string) string {
	if strings.Contains(indexName, "authlog") {
		return "authlog"
	}
	if strings.Contains(indexName, "syslog") {
		return "syslog"
	}
	return "unknown"
}

func indexPatternForLogType(logType string) string {
	for _, candidate := range logTypes {
		if logType == candidate {
			return "logs-" + logType + "-*"
		}
	}
	return elasticsearchIndex
}

func logMatchesExactFilters(log LogRecord, filters Filters) bool {
	if filters.Host != "" && fmt.Sprint(log["host"]) != filters.Host {
		return false
	}
	if filters.Program != "" && fmt.Sprint(log["program"]) != filters.Program {
		return false
	}
	return true
}

func datetimeLocalToISO(value string) string {
	if value == "" {
		return ""
	}
	parsed, err := parseISOTime(value)
	if err != nil {
		return value
	}
	return parsed.In(time.UTC).Format("2006-01-02T15:04:05-07:00")
}

func timeBound(value, direction string, now time.Time) string {
	targetDate := now.In(jst).Format("2006-01-02")
	if value == "" {
		if direction == "from" {
			return targetDate + " 00:00:00"
		}
		return targetDate + " 23:59:59"
	}

	normalized := strings.TrimSpace(value)
	if strings.Contains(normalized, "T") {
		if parsed, err := parseISOTime(normalized); err == nil {
			if parsed.Location() != time.Local {
				parsed = parsed.In(jst)
			}
			return parsed.Format("2006-01-02 15:04:05")
		}
	}

	if parsed, err := time.Parse("15:04:05", addSeconds(normalized)); err == nil {
		return targetDate + " " + parsed.Format("15:04:05")
	}

	if direction == "from" {
		return targetDate + " 00:00:00"
	}
	return targetDate + " 23:59:59"
}

func addSeconds(value string) string {
	if len(strings.Split(value, ":")) == 2 {
		return value + ":00"
	}
	return value
}

func parseISOTime(value string) (time.Time, error) {
	zonedLayouts := []string{
		time.RFC3339Nano,
		"2006-01-02T15:04:05",
		"2006-01-02T15:04",
	}
	for _, layout := range zonedLayouts[:1] {
		if parsed, err := time.Parse(layout, value); err == nil {
			return parsed, nil
		}
	}

	for _, layout := range zonedLayouts[1:] {
		if parsed, err := time.ParseInLocation(layout, value, jst); err == nil {
			return parsed, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid time: %s", value)
}

func formatTimestamp(value any) string {
	if value == nil {
		return ""
	}

	switch v := value.(type) {
	case int64:
		return time.UnixMilli(v).UTC().In(jst).Format("2006/01/02 15:04:05 JST")
	case int:
		return time.UnixMilli(int64(v)).UTC().In(jst).Format("2006/01/02 15:04:05 JST")
	case float64:
		if math.Trunc(v) == v {
			return time.UnixMilli(int64(v)).UTC().In(jst).Format("2006/01/02 15:04:05 JST")
		}
	case json.Number:
		if millis, err := v.Int64(); err == nil {
			return time.UnixMilli(millis).UTC().In(jst).Format("2006/01/02 15:04:05 JST")
		}
	case time.Time:
		if v.Location() == time.Local {
			return v.Format("2006/01/02 15:04:05 JST")
		}
		return v.In(jst).Format("2006/01/02 15:04:05 JST")
	case string:
		if formatted, ok := formatTimestampString(v); ok {
			return formatted
		}
		return v
	}

	return fmt.Sprint(value)
}

func formatTimestampString(value string) (string, bool) {
	trimmed := strings.TrimSpace(value)
	normalized := strings.ReplaceAll(trimmed, " UTC", "Z")
	normalized = strings.ReplaceAll(normalized, " ", "T")
	normalized = strings.ReplaceAll(normalized, "Z", "+00:00")

	layouts := []string{
		time.RFC3339Nano,
		"2006-01-02T15:04:05.999999999-07:00",
		"2006-01-02T15:04:05-07:00",
		"2006-01-02T15:04:05.999999999",
		"2006-01-02T15:04:05",
	}
	for _, layout := range layouts {
		if parsed, err := time.Parse(layout, normalized); err == nil {
			if strings.Contains(layout, "-07:00") || strings.Contains(normalized, "+") {
				return parsed.In(jst).Format("2006/01/02 15:04:05 JST"), true
			}
			return parsed.Format("2006/01/02 15:04:05 JST"), true
		}
	}
	return "", false
}

func (s *SessionStore) Save(w http.ResponseWriter, r *http.Request, filters Filters) {
	id := sessionID(r)
	if id == "" {
		id = randomID()
		http.SetCookie(w, &http.Cookie{
			Name:     "session_id",
			Value:    id,
			Path:     "/",
			HttpOnly: true,
			SameSite: http.SameSiteLaxMode,
		})
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.items[id] = PendingSearch{Filters: filters}
}

func (s *SessionStore) Pop(w http.ResponseWriter, r *http.Request) (PendingSearch, bool) {
	id := sessionID(r)
	if id == "" {
		return PendingSearch{}, false
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	pending, ok := s.items[id]
	if ok {
		delete(s.items, id)
	}
	return pending, ok
}

func (s *SessionStore) Clear(w http.ResponseWriter, r *http.Request) {
	id := sessionID(r)
	if id != "" {
		s.mu.Lock()
		delete(s.items, id)
		s.mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{
		Name:     "session_id",
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
}

func sessionID(r *http.Request) string {
	cookie, err := r.Cookie("session_id")
	if err != nil {
		return ""
	}
	return cookie.Value
}

func randomID() string {
	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(value)
}

func getenv(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func getenvInt(key string, fallback int) int {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}
