require "json"
require "net/http"
require "sinatra/base"
require "time"
require "uri"

class ElasticsearchClient
  def initialize(base_url)
    @base_url = base_url.sub(%r{/+\z}, "")
  end

  def ping
    uri = URI(@base_url)
    response = request(Net::HTTP::Head.new(uri), uri, timeout: 3)
    response.is_a?(Net::HTTPSuccess) || response.is_a?(Net::HTTPRedirection)
  rescue StandardError
    false
  end

  def search(index:, query:, timeout: 15, size: LogSearchApp::DEFAULT_LIMIT)
    uri = URI("#{@base_url}/#{index.to_s.gsub(%r{\A/+|/+\z}, "")}/_search")
    uri.query = URI.encode_www_form(ignore_unavailable: "true")
    http_request = Net::HTTP::Post.new(uri)
    http_request["Content-Type"] = "application/json"
    http_request.body = JSON.generate(
      query: query,
      sort: [{ "@timestamp" => { order: "desc", unmapped_type: "date" } }],
      size: size,
      track_total_hits: false,
      timeout: "5s",
      _source: ["@timestamp", "host", "program", "msg", "severity", "dt", "hr"]
    )

    response = request(http_request, uri, timeout: timeout)
    body = JSON.parse(response.body)
    if body["error"]
      raise "Elasticsearch search failed: #{body["error"]}"
    end
    body.dig("hits", "hits") || []
  end

  private

  def request(request, uri, timeout:)
    http = Net::HTTP.new(uri.host, uri.port)
    http.use_ssl = uri.scheme == "https"
    http.open_timeout = timeout
    http.read_timeout = timeout

    response = http.request(request)
    raise "Elasticsearch HTTP #{response.code}: #{response.body}" unless response.is_a?(Net::HTTPSuccess) || response.is_a?(Net::HTTPRedirection)

    response
  end
end

class LogSearchApp < Sinatra::Base
  ELASTICSEARCH_URL = ENV.fetch("ELASTICSEARCH_URL", "http://elastic1:9200")
  ELASTICSEARCH_INDEX = ENV.fetch("ELASTICSEARCH_INDEX", "logs-*")
  DEFAULT_LIMIT = Integer(ENV.fetch("ELASTICSEARCH_LIMIT", "50"))
  LOG_TYPES = %w[syslog authlog].freeze
  JST_OFFSET = "+09:00"

  get "/" do
    json_response(
      service: "ruby-elastic-backend",
      endpoints: ["/health", "/api/options", "/api/logs"]
    )
  end

  get "/health" do
    json_response(
      ok: client.ping,
      elasticsearch_url: ELASTICSEARCH_URL,
      index: ELASTICSEARCH_INDEX
    )
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
    logs = search_logs(client, filters)
    json_response(filters: filters, count: logs.length, logs: logs)
  rescue StandardError => e
    status 502
    json_response(error: e.message)
  end

  def json_response(payload)
    content_type :json
    JSON.generate(payload)
  end

  def client
    if settings.respond_to?(:elasticsearch_client) && settings.elasticsearch_client
      settings.elasticsearch_client
    else
      ElasticsearchClient.new(ELASTICSEARCH_URL)
    end
  end

  def normalize_filters(source)
    {
      "time_from" => source.fetch("time_from", "").to_s.strip,
      "time_to" => source.fetch("time_to", "").to_s.strip,
      "log_type" => source.fetch("log_type", "").to_s.strip,
      "host" => source.fetch("host", "").to_s.strip,
      "program" => source.fetch("program", "").to_s.strip,
      "message" => source.fetch("message", "").to_s.strip
    }
  end

  def filters_from_hash(source)
    normalize_filters(source.transform_keys(&:to_s))
  end

  def build_query(filters)
    must = []
    filter = []

    must << text_search_clause("msg", filters["message"]) unless filters["message"].empty?
    filter << exact_match_clause("host", filters["host"]) unless filters["host"].empty?
    filter << exact_match_clause("program", filters["program"]) unless filters["program"].empty?

    time_range = {}
    time_range["gte"] = datetime_local_to_iso(filters["time_from"]) unless filters["time_from"].empty?
    time_range["lte"] = datetime_local_to_iso(filters["time_to"]) unless filters["time_to"].empty?
    filter << { range: { "@timestamp" => time_range } } unless time_range.empty?

    return { match_all: {} } if must.empty? && filter.empty?

    bool = {}
    bool[:must] = must unless must.empty?
    bool[:filter] = filter unless filter.empty?
    { bool: bool }
  end

  def text_search_clause(field, value)
    {
      bool: {
        should: [
          { match_phrase: { field => { query: value } } },
          { match: { field => { query: value, operator: "and" } } },
          { wildcard: { field => { value: wildcard_value(value), case_insensitive: true } } },
          { wildcard: { "#{field}.keyword" => { value: wildcard_value(value), case_insensitive: true } } }
        ],
        minimum_should_match: 1
      }
    }
  end

  def exact_match_clause(field, value)
    {
      bool: {
        should: [
          { term: { "#{field}.keyword" => { value: value } } },
          { term: { field => { value: value } } }
        ],
        minimum_should_match: 1
      }
    }
  end

  def wildcard_value(value)
    "*#{value.to_s.gsub("\\", "\\\\\\").gsub("*", "\\*").gsub("?", "\\?")}*"
  end

  def datetime_local_to_iso(value)
    parsed = parse_time(value.to_s.strip)
    parsed ? parsed.utc.iso8601 : value.to_s
  end

  def index_pattern_for_log_type(log_type)
    LOG_TYPES.include?(log_type) ? "logs-#{log_type}-*" : ELASTICSEARCH_INDEX
  end

  def detect_log_type(index_name)
    return "authlog" if index_name.to_s.include?("authlog")
    return "syslog" if index_name.to_s.include?("syslog")

    "unknown"
  end

  def log_matches_exact_filters?(log, filters)
    return false if !filters["host"].empty? && log["host"] != filters["host"]
    return false if !filters["program"].empty? && log["program"] != filters["program"]

    true
  end

  def search_logs(elasticsearch_client, filters)
    hits = elasticsearch_client.search(
      index: index_pattern_for_log_type(filters["log_type"]),
      query: build_query(filters),
      size: DEFAULT_LIMIT
    )
    hits.filter_map do |hit|
      source = hit.fetch("_source", {})
      source.merge(
        "id" => hit["_id"],
        "index" => hit["_index"],
        "log_type" => detect_log_type(hit["_index"]),
        "display_time" => format_timestamp(source["@timestamp"]),
        "score" => hit["_score"]
      ).then { |log| log_matches_exact_filters?(log, filters) ? log : nil }
    end
  end

  def format_timestamp(value)
    return "" if value.nil?
    return Time.at(value / 1000.0).getlocal(JST_OFFSET).strftime("%Y/%m/%d %H:%M:%S JST") if value.is_a?(Numeric)

    parsed = parse_time(value.to_s.strip)
    parsed ? parsed.getlocal(JST_OFFSET).strftime("%Y/%m/%d %H:%M:%S JST") : value.to_s
  end

  def parse_time(value)
    normalized = value.sub(/ UTC\z/, "Z").tr(" ", "T")
    with_zone = normalized.sub(/Z\z/, "+00:00")
    return Time.iso8601(with_zone) if with_zone.match?(/(?:[+-]\d{2}:?\d{2})\z/)

    match = normalized.match(/\A(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?\z/)
    return nil unless match

    year, month, day, hour, minute, second = match.captures
    Time.new(year.to_i, month.to_i, day.to_i, hour.to_i, minute.to_i, second.to_i, JST_OFFSET)
  rescue ArgumentError
    nil
  end

  run! if app_file == $PROGRAM_NAME
end
