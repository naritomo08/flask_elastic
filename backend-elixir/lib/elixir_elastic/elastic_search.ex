defmodule ElixirElastic.ElasticSearch do
  @moduledoc false

  @log_types ["syslog", "authlog"]
  @jst_offset_seconds 9 * 60 * 60

  def log_types, do: @log_types

  def ping do
    case Req.get(elasticsearch_url(), receive_timeout: 3_000) do
      {:ok, %{status: status}} when status in 200..399 -> true
      _ -> false
    end
  end

  def search_logs(filters) do
    index = index_pattern_for_log_type(filters["log_type"])

    body = %{
      query: build_query(filters),
      sort: [%{"@timestamp" => %{order: "desc", unmapped_type: "date"}}],
      size: 50,
      track_total_hits: false,
      timeout: "5s",
      _source: ["@timestamp", "host", "program", "msg", "severity", "dt", "hr"]
    }

    url = "#{elasticsearch_url()}/#{encode_index(index)}/_search"

    case Req.post(url, json: body, params: [ignore_unavailable: true], receive_timeout: 10_000) do
      {:ok, %{status: status, body: response}} when status in 200..299 ->
        response
        |> get_in(["hits", "hits"])
        |> Kernel.||([])
        |> Enum.map(&format_hit/1)
        |> Enum.filter(&matches_exact_filters?(&1, filters))

      {:ok, %{status: status, body: response}} ->
        raise "Elasticsearch search failed with status #{status}: #{inspect(response)}"

      {:error, reason} ->
        raise "Elasticsearch search failed: #{inspect(reason)}"
    end
  end

  def build_query(filters) do
    must =
      []
      |> append_match(filters["message"], "msg")

    filter_clauses =
      []
      |> append_exact_filter(filters["host"], "host")
      |> append_exact_filter(filters["program"], "program")
      |> append_time_filter(filters["time_from"], filters["time_to"])

    cond do
      must == [] and filter_clauses == [] ->
        %{match_all: %{}}

      must == [] ->
        %{bool: %{filter: filter_clauses}}

      filter_clauses == [] ->
        %{bool: %{must: must}}

      true ->
        %{bool: %{must: must, filter: filter_clauses}}
    end
  end

  def format_timestamp(nil), do: ""

  def format_timestamp(value) when is_integer(value) or is_float(value) do
    value
    |> Kernel./(1000)
    |> trunc()
    |> DateTime.from_unix!()
    |> DateTime.add(@jst_offset_seconds, :second)
    |> Calendar.strftime("%Y/%m/%d %H:%M:%S JST")
  end

  def format_timestamp(value) when is_binary(value) do
    normalized = String.replace(value, "Z", "+00:00")

    case DateTime.from_iso8601(normalized) do
      {:ok, datetime, _offset} ->
        datetime
        |> DateTime.add(@jst_offset_seconds, :second)
        |> Calendar.strftime("%Y/%m/%d %H:%M:%S JST")

      {:error, _reason} ->
        value
    end
  end

  def format_timestamp(value), do: to_string(value)

  def datetime_local_to_iso(""), do: ""
  def datetime_local_to_iso(nil), do: ""

  def datetime_local_to_iso(value) do
    with {:ok, naive} <- NaiveDateTime.from_iso8601(add_seconds(value)),
         {:ok, utc} <-
           DateTime.from_naive(NaiveDateTime.add(naive, -@jst_offset_seconds, :second), "Etc/UTC") do
      DateTime.to_iso8601(utc)
    else
      _ -> value
    end
  end

  def detect_log_type(index_name) do
    cond do
      String.contains?(index_name, "authlog") -> "authlog"
      String.contains?(index_name, "syslog") -> "syslog"
      true -> "unknown"
    end
  end

  def index_pattern_for_log_type(log_type) when log_type in @log_types, do: "logs-#{log_type}-*"

  def index_pattern_for_log_type(_log_type),
    do: Application.fetch_env!(:elixir_elastic, :elasticsearch_index)

  defp append_match(must, nil, _field), do: must
  defp append_match(must, "", _field), do: must

  defp append_match(must, value, field) do
    must ++
      [
        %{
          bool: %{
            should: [
              %{match: %{field => %{query: value, operator: "and"}}},
              %{match_phrase: %{field => %{query: value}}},
              %{
                wildcard: %{
                  "#{field}.keyword" => %{
                    value: "*#{wildcard_escape(value)}*",
                    case_insensitive: true
                  }
                }
              },
              %{
                wildcard: %{
                  field => %{value: "*#{wildcard_escape(value)}*", case_insensitive: true}
                }
              }
            ],
            minimum_should_match: 1
          }
        }
      ]
  end

  defp append_exact_filter(filters, nil, _field), do: filters
  defp append_exact_filter(filters, "", _field), do: filters

  defp append_exact_filter(filters, value, field) do
    filters ++
      [
        %{
          bool: %{
            should: [
              %{term: %{field => value}},
              %{term: %{"#{field}.keyword" => value}}
            ],
            minimum_should_match: 1
          }
        }
      ]
  end

  defp append_time_filter(filters, "", ""), do: filters
  defp append_time_filter(filters, nil, nil), do: filters

  defp append_time_filter(filters, time_from, time_to) do
    range =
      %{}
      |> maybe_put("gte", datetime_local_to_iso(time_from))
      |> maybe_put("lte", datetime_local_to_iso(time_to))

    if range == %{}, do: filters, else: filters ++ [%{range: %{"@timestamp" => range}}]
  end

  defp maybe_put(map, _key, ""), do: map
  defp maybe_put(map, _key, nil), do: map
  defp maybe_put(map, key, value), do: Map.put(map, key, value)

  defp wildcard_escape(value) do
    value
    |> String.replace("\\", "\\\\")
    |> String.replace("*", "\\*")
    |> String.replace("?", "\\?")
  end

  defp format_hit(hit) do
    source = Map.get(hit, "_source", %{})
    index = Map.get(hit, "_index", "")

    source
    |> Map.put("id", Map.get(hit, "_id"))
    |> Map.put("index", index)
    |> Map.put("log_type", detect_log_type(index))
    |> Map.put("display_time", format_timestamp(Map.get(source, "@timestamp")))
    |> Map.put("score", Map.get(hit, "_score"))
  end

  defp matches_exact_filters?(log, filters) do
    matches_filter?(log, filters, "host") and matches_filter?(log, filters, "program")
  end

  defp matches_filter?(log, filters, field) do
    expected = Map.get(filters, field, "")
    expected == "" or Map.get(log, field, "") == expected
  end

  defp add_seconds(value) do
    if String.length(value) == 16, do: value <> ":00", else: value
  end

  defp elasticsearch_url do
    Application.fetch_env!(:elixir_elastic, :elasticsearch_url)
  end

  defp encode_index(index) do
    String.replace(index, "*", "%2A")
  end
end
