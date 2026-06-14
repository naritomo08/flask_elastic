ENV["RACK_ENV"] = "test"

require "json"
require "minitest/autorun"
require "rack/test"
require_relative "app"

class FakeElasticsearch
  attr_reader :searches

  def initialize
    @searches = []
  end

  def ping
    true
  end

  def search(index:, query:, size: LogSearchApp::DEFAULT_LIMIT, timeout: 15)
    @searches << { index: index, query: query, size: size, timeout: timeout }
    [
      {
        "_id" => "1",
        "_index" => ".ds-logs-syslog-2026.06.02-000001",
        "_score" => 1.0,
        "_source" => {
          "@timestamp" => 1_780_398_715_000,
          "host" => "flink1",
          "program" => "systemd",
          "msg" => "Reached target sshd-keygen.target.",
          "severity" => 6
        }
      }
    ]
  end
end

class LogSearchAppTest < Minitest::Test
  include Rack::Test::Methods

  def app
    LogSearchApp
  end

  def setup
    @fake_client = FakeElasticsearch.new
    LogSearchApp.set :elasticsearch_client, @fake_client
  end

  def teardown
    LogSearchApp.set :elasticsearch_client, nil
  end

  def test_format_timestamp_converts_epoch_millis_to_jst
    assert_equal "2026/06/02 20:11:55 JST", app.new!.format_timestamp(1_780_398_715_000)
  end

  def test_format_timestamp_keeps_naive_timestamp_as_jst
    assert_equal "2026/06/02 20:11:55 JST", app.new!.format_timestamp("2026-06-02 20:11:55.000")
  end

  def test_datetime_local_to_iso_treats_input_as_jst
    instance = app.new!

    assert_equal "2026-06-02T11:11:00Z", instance.datetime_local_to_iso("2026-06-02T20:11")
  end

  def test_wildcard_value_escapes_elasticsearch_wildcards
    instance = app.new!

    assert_equal "*a\\\\b\\*c\\?*", instance.wildcard_value("a\\b*c?")
  end

  def test_build_query_with_message_program_host_and_time_range
    instance = app.new!

    query = instance.build_query({
      "time_from" => "2026-06-02T20:00",
      "time_to" => "2026-06-02T21:00",
      "log_type" => "syslog",
      "host" => "flink1",
      "program" => "systemd",
      "message" => "sshd"
    })

    query_json = JSON.generate(query)
    assert_includes query_json, '"match_phrase"'
    assert_includes query_json, '"match"'
    assert_includes query_json, '"query":"sshd"'
    assert_includes query_json, '"operator":"and"'
    assert_includes query_json, '"host.keyword"'
    assert_includes query_json, '"program.keyword"'
    assert_includes query_json, '"gte":"2026-06-02T11:00:00Z"'
    assert_includes query_json, '"lte":"2026-06-02T12:00:00Z"'
  end

  def test_build_query_supports_space_separated_message_search
    instance = app.new!
    query = instance.build_query(instance.normalize_filters("message" => "authlog forward test from"))
    query_json = JSON.generate(query)

    assert_includes query_json, '"match_phrase"'
    assert_includes query_json, '"wildcard"'
    assert_includes query_json, '"msg.keyword"'
    assert_includes query_json, '"value":"*authlog forward test from*"'
    assert_includes query_json, '"case_insensitive":true'
  end

  def test_index_serves_static_html_client
    get "/"
    body = last_response.body.force_encoding("UTF-8")

    assert_equal 200, last_response.status
    assert_includes body, %(action="/api/logs")
    assert_includes body, %(id="search-form")
    assert_includes body, %(src="/search.js")
    assert_includes body, %(type="datetime-local")
    assert_includes body, "検索を実施してください"
    refute_includes body, "2026/06/02 20:11:55 JST"
  end

  def test_post_index_redirects_to_static_html
    post "/", { program: "systemd", message: "sshd" }
    assert_equal 302, last_response.status
    assert_equal "http://example.org/", last_response.location

    follow_redirect!
    body = last_response.body.force_encoding("UTF-8")

    assert_includes body, "検索を実施してください"
    refute_includes body, "2026/06/02 20:11:55 JST"
  end

  def test_post_api_logs_accepts_json
    post "/api/logs", JSON.generate({ program: "systemd", message: "sshd" }), "CONTENT_TYPE" => "application/json"

    assert_equal 200, last_response.status
    payload = JSON.parse(last_response.body)
    assert_equal 1, payload["count"]
    assert_equal "systemd", payload["filters"]["program"]
    assert_equal "2026/06/02 20:11:55 JST", payload["logs"][0]["display_time"]
    assert_equal "logs-*", @fake_client.searches[0][:index]
  end
end
