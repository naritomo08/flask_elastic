require "json"
require "net/http"
require "uri"

class ElasticsearchClient
  def initialize(base_url)
    @base_url = base_url.sub(%r{/+\z}, "")
  end

  def health
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    uri = URI(@base_url)
    response = request(Net::HTTP::Get.new(uri), uri, timeout: 3)
    payload = JSON.parse(response.body)
    {
      ok: true, status: "ok", backend: "ruby",
      latency_ms: elapsed_ms(started),
      cluster_name: payload["cluster_name"].to_s,
      version: payload.dig("version", "number").to_s
    }
  rescue StandardError => e
    { ok: false, status: "error", backend: "ruby", latency_ms: elapsed_ms(started), error: e.message }
  end

  def search(index:, query:, timeout: 15, page: 1, size: 20)
    uri = URI("#{@base_url}/#{index.to_s.gsub(%r{\A/+|/+\z}, "")}/_search")
    uri.query = URI.encode_www_form(ignore_unavailable: "true")
    http_request = Net::HTTP::Post.new(uri)
    http_request["Content-Type"] = "application/json"
    http_request.body = JSON.generate(
      query: query,
      sort: [{ "@timestamp" => { order: "desc", unmapped_type: "date" } }],
      from: (page - 1) * size, size: size, track_total_hits: true, timeout: "5s",
      _source: ["@timestamp", "host", "program", "msg", "severity", "dt", "hr"]
    )
    body = JSON.parse(request(http_request, uri, timeout: timeout).body)
    raise "Elasticsearch search failed: #{body["error"]}" if body["error"]

    total_value = body.dig("hits", "total") || 0
    {
      "total" => total_value.is_a?(Hash) ? total_value.fetch("value", 0).to_i : total_value.to_i,
      "hits" => body.dig("hits", "hits") || []
    }
  end

  private

  def elapsed_ms(started)
    ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - started) * 1000).round
  end

  def request(request, uri, timeout:)
    http = Net::HTTP.new(uri.host, uri.port)
    http.use_ssl = uri.scheme == "https"
    http.open_timeout = timeout
    http.read_timeout = timeout
    response = http.request(request)
    unless response.is_a?(Net::HTTPSuccess) || response.is_a?(Net::HTTPRedirection)
      raise "Elasticsearch HTTP #{response.code}: #{response.body}"
    end
    response
  end
end
