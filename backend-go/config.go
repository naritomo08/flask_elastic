package main

import (
	"os"
	"strconv"
	"time"
)

var (
	elasticsearchURL   = getenv("ELASTICSEARCH_URL", "http://elastic1:9200")
	elasticsearchIndex = getenv("ELASTICSEARCH_INDEX", "logs-*")
	defaultLimit       = getenvInt("ELASTICSEARCH_LIMIT", 50)
	jst                = time.FixedZone("JST", 9*60*60)
	logTypes           = []string{"syslog", "authlog"}
)

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
