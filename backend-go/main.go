package main

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"
)

func main() {
	app := NewApp(NewElasticClient(elasticsearchURL))
	server := &http.Server{
		Addr:              ":5000",
		Handler:           app.routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}

func NewApp(client ElasticSearcher) *App {
	return &App{client: client}
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

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	startedAt := time.Now()
	info, infoErr := a.client.Info(r.Context())
	ok := infoErr == nil
	payload := map[string]any{
		"ok": ok, "status": map[bool]string{true: "ok", false: "error"}[ok], "backend": "go",
		"elasticsearch_url": elasticsearchURL, "index": elasticsearchIndex,
		"latency_ms": time.Since(startedAt).Milliseconds(),
	}
	if ok {
		payload["cluster_name"] = stringValue(info["cluster_name"])
		if version, found := info["version"].(map[string]any); found {
			payload["version"] = stringValue(version["number"])
		}
	} else {
		payload["error"] = infoErr.Error()
	}
	writeJSON(w, payload)
}

func (a *App) apiOptions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, map[string]any{"log_types": logTypes})
}

func (a *App) apiSearchLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	filters, err := filtersFromRequest(r)
	if err != nil {
		writeJSONStatus(w, http.StatusBadRequest, map[string]any{"error": err.Error()})
		return
	}
	page, size := paginationFromRequest(r)
	result, err := searchLogs(r.Context(), a.client, filters, page, size)
	if err != nil {
		writeJSONStatus(w, http.StatusBadGateway, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{
		"filters": filters, "total": result.Total, "page": page, "size": size,
		"results": result.Logs, "count": len(result.Logs), "logs": result.Logs,
	})
}

func paginationFromRequest(r *http.Request) (int, int) {
	values := r.URL.Query()
	if r.Method == http.MethodPost && !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		_ = r.ParseForm()
		values = r.PostForm
	}
	return positiveInt(values.Get("page"), 1, 0), positiveInt(values.Get("size"), 20, 100)
}

func filtersFromRequest(r *http.Request) (Filters, error) {
	if strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
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
		TimeFrom: values.Get("time_from"), TimeTo: values.Get("time_to"),
		LogType: values.Get("log_type"), Host: values.Get("host"),
		Program: values.Get("program"), Message: values.Get("message"),
	})
}

func normalizeFilters(filters Filters) Filters {
	return Filters{
		TimeFrom: strings.TrimSpace(filters.TimeFrom), TimeTo: strings.TrimSpace(filters.TimeTo),
		LogType: strings.TrimSpace(filters.LogType), Host: strings.TrimSpace(filters.Host),
		Program: strings.TrimSpace(filters.Program), Message: strings.TrimSpace(filters.Message),
	}
}

func writeJSON(w http.ResponseWriter, value any) {
	writeJSONStatus(w, http.StatusOK, value)
}

func writeJSONStatus(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(value)
}
