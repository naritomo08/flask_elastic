package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

type fakeElastic struct {
	searches []fakeSearch
}

type fakeSearch struct {
	index string
	query map[string]any
}

func (f *fakeElastic) Ping(ctx context.Context) bool {
	return true
}

func (f *fakeElastic) Search(ctx context.Context, index string, query map[string]any, timeout time.Duration) ([]ElasticHit, error) {
	f.searches = append(f.searches, fakeSearch{index: index, query: query})
	return []ElasticHit{{
		ID:    "1",
		Index: ".ds-logs-syslog-2026.06.02-000001",
		Score: jsonNumber("1.0"),
		Source: map[string]any{
			"@timestamp": jsonNumber("1780398715000"),
			"host":       "flink1",
			"program":    "systemd",
			"msg":        "Reached target sshd-keygen.target.",
			"severity":   jsonNumber("6"),
		},
	}}, nil
}

func TestFormatTimestampConvertsEpochMillisToJST(t *testing.T) {
	got := formatTimestamp(int64(1780398715000))
	if got != "2026/06/02 20:11:55 JST" {
		t.Fatalf("formatTimestamp() = %q", got)
	}
}

func TestFormatTimestampKeepsNaiveTimestampAsJST(t *testing.T) {
	got := formatTimestamp("2026-06-02 20:11:55.000")
	if got != "2026/06/02 20:11:55 JST" {
		t.Fatalf("formatTimestamp() = %q", got)
	}
}

func TestDatetimeLocalToISOTreatsInputAsJST(t *testing.T) {
	if got := datetimeLocalToISO("2026-06-02T20:11"); got != "2026-06-02T11:11:00+00:00" {
		t.Fatalf("datetimeLocalToISO() = %q", got)
	}
}

func TestWildcardValueEscapesElasticsearchWildcards(t *testing.T) {
	if got := wildcardValue(`a\b*c?`); got != `*a\\b\*c\?*` {
		t.Fatalf("wildcardValue() = %q", got)
	}
}

func TestBuildQueryWithMessageProgramHostAndTimeRange(t *testing.T) {
	filters := Filters{
		TimeFrom: "2026-06-02T20:00",
		TimeTo:   "2026-06-02T21:00",
		LogType:  "syslog",
		Host:     "flink1",
		Program:  "systemd",
		Message:  "sshd",
	}

	query := buildQuery(filters)
	queryJSON := mustJSON(t, query)
	assertContains(t, queryJSON, `"match_phrase":{"msg":{"query":"sshd"}}`)
	assertContains(t, queryJSON, `"match":{"msg":{"operator":"and","query":"sshd"}}`)
	assertContains(t, queryJSON, `"term":{"host.keyword":{"value":"flink1"}}`)
	assertContains(t, queryJSON, `"term":{"program.keyword":{"value":"systemd"}}`)
	assertContains(t, queryJSON, `"range":{"@timestamp":{"gte":"2026-06-02T11:00:00+00:00","lte":"2026-06-02T12:00:00+00:00"}}`)
}

func TestBuildQuerySupportsSpaceSeparatedMessageSearch(t *testing.T) {
	query := buildQuery(normalizeFilters(Filters{Message: "authlog forward test from"}))
	queryJSON := mustJSON(t, query)
	assertContains(t, queryJSON, `"match_phrase":{"msg":{"query":"authlog forward test from"}}`)
	assertContains(t, queryJSON, `"wildcard":{"msg":{"case_insensitive":true,"value":"*authlog forward test from*"}}`)
	assertContains(t, queryJSON, `"wildcard":{"msg.keyword":{"case_insensitive":true,"value":"*authlog forward test from*"}}`)
}

func TestSearchLogsUsesElasticsearchIndexAndFormatsResult(t *testing.T) {
	client := &fakeElastic{}
	logs, err := searchLogs(context.Background(), client, Filters{
		LogType: "syslog",
		Program: "systemd",
		Message: "sshd",
	})
	if err != nil {
		t.Fatal(err)
	}
	if client.searches[0].index != "logs-syslog-*" {
		t.Fatalf("index = %q", client.searches[0].index)
	}
	if logs[0]["display_time"] != "2026/06/02 20:11:55 JST" {
		t.Fatalf("display_time = %v", logs[0]["display_time"])
	}
	if logs[0]["log_type"] != "syslog" {
		t.Fatalf("log_type = %v", logs[0]["log_type"])
	}
}

func TestPostIndexSearchKeepsFiltersInBodyOnce(t *testing.T) {
	client := &fakeElastic{}
	app, err := NewApp(client)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(app.routes())
	defer server.Close()

	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	httpClient := &http.Client{Jar: jar}
	form := url.Values{"program": {"systemd"}, "message": {"sshd"}}
	resp, err := httpClient.PostForm(server.URL+"/", form)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	body := responseBody(t, resp)
	assertContains(t, body, `method="post"`)
	assertContains(t, body, `id="search-form"`)
	assertContains(t, body, `id="results-summary"`)
	assertContains(t, body, `id="results-body"`)
	assertContains(t, body, `src="/static/search.js"`)
	assertContains(t, body, `type="datetime-local"`)
	assertContains(t, body, `value="systemd"`)
	assertContains(t, body, `value="sshd"`)
	assertContains(t, body, "2026/06/02 20:11:55 JST")
}

func TestPostAPILogsAcceptsJSON(t *testing.T) {
	client := &fakeElastic{}
	app, err := NewApp(client)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(app.routes())
	defer server.Close()

	resp, err := http.Post(server.URL+"/api/logs", "application/json", strings.NewReader(`{"program":"systemd","message":"sshd"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	body := responseBody(t, resp)
	assertContains(t, body, `"count":1`)
	assertContains(t, body, `"program":"systemd"`)
	assertContains(t, body, `"display_time":"2026/06/02 20:11:55 JST"`)
	if client.searches[0].index != "logs-*" {
		t.Fatalf("index = %q", client.searches[0].index)
	}
}

func TestEmptyAPISearchUsesMatchAll(t *testing.T) {
	client := &fakeElastic{}
	app, err := NewApp(client)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(app.routes())
	defer server.Close()

	resp, err := http.Post(server.URL+"/api/logs", "application/json", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	body := responseBody(t, resp)
	assertContains(t, body, `"count":1`)
	queryJSON := mustJSON(t, client.searches[0].query)
	if queryJSON != `{"match_all":{}}` {
		t.Fatalf("query = %s", queryJSON)
	}
}

func assertContains(t *testing.T, value, needle string) {
	t.Helper()
	if !strings.Contains(value, needle) {
		t.Fatalf("expected %q to contain %q", value, needle)
	}
}

func assertNotContains(t *testing.T, value, needle string) {
	t.Helper()
	if strings.Contains(value, needle) {
		t.Fatalf("expected %q not to contain %q", value, needle)
	}
}

func responseBody(t *testing.T, resp *http.Response) string {
	t.Helper()
	buf := new(strings.Builder)
	_, err := io.Copy(buf, resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func mustJSON(t *testing.T, value any) string {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func jsonNumber(value string) json.Number {
	return json.Number(value)
}
