package com.example.flaskelastic;

record Config(String port, String elasticsearchUrl, String elasticsearchIndex, int elasticsearchLimit) {
    static Config fromEnv() {
        return new Config(
                Values.getenv("PORT", "5000"),
                Values.getenv("ELASTICSEARCH_URL", "http://elastic1:9200"),
                Values.getenv("ELASTICSEARCH_INDEX", "logs-*"),
                Values.getenvInt("ELASTICSEARCH_LIMIT", 50)
        );
    }
}
