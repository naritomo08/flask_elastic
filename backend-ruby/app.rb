require "json"
require "sinatra/base"

require_relative "lib/elasticsearch_client"
require_relative "lib/log_search"

class LogSearchApp < Sinatra::Base
  include LogSearch

  ELASTICSEARCH_URL = ENV.fetch("ELASTICSEARCH_URL", "http://elastic1:9200")
  ELASTICSEARCH_INDEX = ENV.fetch("ELASTICSEARCH_INDEX", "logs-*")
  LOG_TYPES = %w[syslog authlog].freeze

  get "/" do
    json_response(service: "ruby-elastic-backend", endpoints: ["/health", "/api/options", "/api/logs"])
  end

  get "/health" do
    json_response(client.health.merge(elasticsearch_url: ELASTICSEARCH_URL, index: ELASTICSEARCH_INDEX))
  end

  get "/api/options" do
    json_response(log_types: LOG_TYPES)
  end

  get "/api/logs" do
    api_search_logs(filters_from_hash(params))
  end

  post "/api/logs" do
    filters =
      if request.media_type == "application/json"
        body = request.body.read
        filters_from_hash(body.empty? ? {} : JSON.parse(body))
      else
        filters_from_hash(params)
      end
    api_search_logs(filters)
  end

  def api_search_logs(filters)
    page = positive_int(params["page"], 1)
    size = positive_int(params["size"], 20, 100)
    result = search_logs(client, filters, page, size)
    json_response(
      filters: filters, total: result["total"], page: page, size: size,
      results: result["results"], count: result["results"].length, logs: result["results"]
    )
  rescue StandardError => e
    status 502
    json_response(error: e.message)
  end

  def positive_int(value, fallback, maximum = nil)
    parsed = Integer(value || fallback)
    parsed = fallback if parsed < 1
    maximum ? [parsed, maximum].min : parsed
  rescue ArgumentError, TypeError
    fallback
  end

  def json_response(payload)
    content_type :json
    JSON.generate(payload)
  end

  def client
    return settings.elasticsearch_client if settings.respond_to?(:elasticsearch_client) && settings.elasticsearch_client
    ElasticsearchClient.new(ELASTICSEARCH_URL)
  end

  run! if app_file == $PROGRAM_NAME
end
