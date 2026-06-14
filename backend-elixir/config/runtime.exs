import Config

config :elixir_elastic,
  elasticsearch_url: System.get_env("ELASTICSEARCH_URL", "http://elastic1:9200"),
  elasticsearch_index: System.get_env("ELASTICSEARCH_INDEX", "logs-*"),
  port: String.to_integer(System.get_env("PORT", "5000"))
