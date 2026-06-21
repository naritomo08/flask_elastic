defmodule ElixirElastic.ElasticSearch do
  @moduledoc false

  alias ElixirElastic.{LogFormatter, Query}

  @log_types ["syslog", "authlog"]

  def log_types, do: @log_types

  def ping do
    case Req.get(elasticsearch_url(), receive_timeout: 3_000) do
      {:ok, %{status: status}} when status in 200..399 -> true
      _ -> false
    end
  end

  def health do
    started_at = System.monotonic_time(:millisecond)

    case Req.get(elasticsearch_url(), receive_timeout: 3_000) do
      {:ok, %{status: status, body: body}} when status in 200..399 ->
        %{
          ok: true,
          status: "ok",
          backend: "elixir",
          latency_ms: System.monotonic_time(:millisecond) - started_at,
          cluster_name: Map.get(body, "cluster_name", ""),
          version: get_in(body, ["version", "number"]) || ""
        }

      {:ok, %{status: status}} ->
        %{
          ok: false,
          status: "error",
          backend: "elixir",
          latency_ms: System.monotonic_time(:millisecond) - started_at,
          error: "HTTP #{status}"
        }

      {:error, reason} ->
        %{
          ok: false,
          status: "error",
          backend: "elixir",
          latency_ms: System.monotonic_time(:millisecond) - started_at,
          error: inspect(reason)
        }
    end
  end

  def search_logs(filters, page \\ 1, size \\ 20) do
    body = %{
      query: Query.build(filters),
      sort: [%{"@timestamp" => %{order: "desc", unmapped_type: "date"}}],
      from: (page - 1) * size,
      size: size,
      track_total_hits: true,
      timeout: "5s",
      _source: ["@timestamp", "host", "program", "msg", "severity", "dt", "hr"]
    }

    url =
      "#{elasticsearch_url()}/#{encode_index(index_pattern_for_log_type(filters["log_type"]))}/_search"

    case Req.post(url, json: body, params: [ignore_unavailable: true], receive_timeout: 10_000) do
      {:ok, %{status: status, body: response}} when status in 200..299 ->
        total_value = get_in(response, ["hits", "total"]) || 0
        total = if is_map(total_value), do: Map.get(total_value, "value", 0), else: total_value

        results =
          (get_in(response, ["hits", "hits"]) || [])
          |> Enum.map(&LogFormatter.format_hit/1)
          |> Enum.filter(&LogFormatter.matches_exact_filters?(&1, filters))

        %{total: total, page: page, size: size, results: results}

      {:ok, %{status: status, body: response}} ->
        raise "Elasticsearch search failed with status #{status}: #{inspect(response)}"

      {:error, reason} ->
        raise "Elasticsearch search failed: #{inspect(reason)}"
    end
  end

  def index_pattern_for_log_type(log_type) when log_type in @log_types, do: "logs-#{log_type}-*"

  def index_pattern_for_log_type(_),
    do: Application.fetch_env!(:elixir_elastic, :elasticsearch_index)

  defp elasticsearch_url, do: Application.fetch_env!(:elixir_elastic, :elasticsearch_url)
  defp encode_index(index), do: String.replace(index, "*", "%2A")
end
