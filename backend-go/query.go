package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
)

func searchLogs(ctx context.Context, client ElasticSearcher, filters Filters, page, size int) (LogSearchResult, error) {
	result, err := client.Search(ctx, indexPatternForLogType(filters.LogType), buildQuery(filters), page, size, 15*time.Second)
	if err != nil {
		return LogSearchResult{}, err
	}
	logs := make([]LogRecord, 0, len(result.Hits))
	for _, hit := range result.Hits {
		logRecord := LogRecord{"id": hit.ID, "index": hit.Index, "log_type": detectLogType(hit.Index), "score": hit.Score}
		for key, value := range hit.Source {
			logRecord[key] = value
		}
		logRecord["display_time"] = formatTimestamp(logRecord["@timestamp"])
		if logMatchesExactFilters(logRecord, filters) {
			logs = append(logs, logRecord)
		}
	}
	return LogSearchResult{Total: result.Total, Logs: logs}, nil
}

func buildQuery(filters Filters) map[string]any {
	must := make([]any, 0, 1)
	filterClauses := make([]any, 0, 3)
	if filters.Message != "" {
		must = append(must, textSearchClause("msg", filters.Message))
	}
	if filters.Program != "" {
		filterClauses = append(filterClauses, exactOrRegexClause("program", filters.Program))
	}
	if filters.Host != "" {
		filterClauses = append(filterClauses, exactOrRegexClause("host", filters.Host))
	}
	timeRange := map[string]any{}
	if filters.TimeFrom != "" {
		timeRange["gte"] = datetimeLocalToISO(filters.TimeFrom)
	}
	if filters.TimeTo != "" {
		timeRange["lte"] = datetimeLocalToISO(filters.TimeTo)
	}
	if len(timeRange) > 0 {
		filterClauses = append(filterClauses, map[string]any{"range": map[string]any{"@timestamp": timeRange}})
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
	return map[string]any{"bool": map[string]any{
		"should": []any{
			map[string]any{"match_phrase": map[string]any{field: map[string]any{"query": value}}},
			map[string]any{"match": map[string]any{field: map[string]any{"query": value, "operator": "and"}}},
			map[string]any{"wildcard": map[string]any{field: map[string]any{"value": wildcardValue(value), "case_insensitive": true}}},
			map[string]any{"wildcard": map[string]any{field + ".keyword": map[string]any{"value": wildcardValue(value), "case_insensitive": true}}},
		}, "minimum_should_match": 1,
	}}
}

func exactMatchClause(field, value string) map[string]any {
	return map[string]any{"bool": map[string]any{
		"should": []any{
			map[string]any{"term": map[string]any{field + ".keyword": map[string]any{"value": value}}},
			map[string]any{"term": map[string]any{field: map[string]any{"value": value}}},
		}, "minimum_should_match": 1,
	}}
}

func exactOrRegexClause(field, value string) map[string]any {
	pattern, ok := regexPattern(value)
	if !ok {
		return exactMatchClause(field, value)
	}
	return map[string]any{"bool": map[string]any{
		"should": []any{
			map[string]any{"regexp": map[string]any{field + ".keyword": map[string]any{"value": pattern, "case_insensitive": true}}},
			map[string]any{"regexp": map[string]any{field: map[string]any{"value": pattern, "case_insensitive": true}}},
		}, "minimum_should_match": 1,
	}}
}

func regexPattern(value string) (string, bool) {
	if len(value) >= 2 && strings.HasPrefix(value, "/") && strings.HasSuffix(value, "/") {
		return value[1 : len(value)-1], true
	}
	return "", false
}

func wildcardValue(value string) string {
	escaped := strings.ReplaceAll(value, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `*`, `\*`)
	escaped = strings.ReplaceAll(escaped, `?`, `\?`)
	return "*" + escaped + "*"
}

func indexPatternForLogType(logType string) string {
	for _, candidate := range logTypes {
		if logType == candidate {
			return "logs-" + logType + "-*"
		}
	}
	return elasticsearchIndex
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

func logMatchesExactFilters(log LogRecord, filters Filters) bool {
	if _, regex := regexPattern(filters.Host); filters.Host != "" && !regex && stringValue(log["host"]) != filters.Host {
		return false
	}
	if _, regex := regexPattern(filters.Program); filters.Program != "" && !regex && stringValue(log["program"]) != filters.Program {
		return false
	}
	return true
}

func datetimeLocalToISO(value string) string {
	parsed, err := parseISOTime(value)
	if err != nil {
		return value
	}
	return parsed.In(time.UTC).Format("2006-01-02T15:04:05-07:00")
}

func parseISOTime(value string) (time.Time, error) {
	if parsed, err := time.Parse(time.RFC3339Nano, value); err == nil {
		return parsed, nil
	}
	for _, layout := range []string{"2006-01-02T15:04:05", "2006-01-02T15:04"} {
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
		return formatMillis(v)
	case int:
		return formatMillis(int64(v))
	case float64:
		if math.Trunc(v) == v {
			return formatMillis(int64(v))
		}
	case json.Number:
		if millis, err := v.Int64(); err == nil {
			return formatMillis(millis)
		}
	case time.Time:
		return v.In(jst).Format("2006/01/02 15:04:05 JST")
	case string:
		if formatted, ok := formatTimestampString(v); ok {
			return formatted
		}
		return v
	}
	return stringValue(value)
}

func formatMillis(value int64) string {
	return time.UnixMilli(value).UTC().In(jst).Format("2006/01/02 15:04:05 JST")
}

func formatTimestampString(value string) (string, bool) {
	normalized := strings.ReplaceAll(strings.ReplaceAll(strings.TrimSpace(value), " UTC", "Z"), " ", "T")
	normalized = strings.ReplaceAll(normalized, "Z", "+00:00")
	for _, layout := range []string{
		time.RFC3339Nano, "2006-01-02T15:04:05.999999999-07:00",
		"2006-01-02T15:04:05-07:00", "2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05",
	} {
		if parsed, err := time.Parse(layout, normalized); err == nil {
			if strings.Contains(layout, "-07:00") || strings.Contains(normalized, "+") {
				return parsed.In(jst).Format("2006/01/02 15:04:05 JST"), true
			}
			return parsed.Format("2006/01/02 15:04:05 JST"), true
		}
	}
	return "", false
}

func totalHits(value any) int {
	switch typed := value.(type) {
	case map[string]any:
		return positiveInt(stringValue(typed["value"]), 0, 0)
	case json.Number:
		number, _ := typed.Int64()
		return int(number)
	case float64:
		return int(typed)
	default:
		return positiveInt(stringValue(value), 0, 0)
	}
}

func positiveInt(value string, fallback, maximum int) int {
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed < 1 {
		parsed = fallback
	}
	if maximum > 0 && parsed > maximum {
		return maximum
	}
	return parsed
}

func stringValue(value any) string {
	return fmt.Sprint(value)
}
