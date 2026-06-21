defmodule ElixirElastic.LogFormatter do
  @moduledoc false

  alias ElixirElastic.Query

  @jst_offset_seconds 9 * 60 * 60

  def format_hit(hit) do
    source = Map.get(hit, "_source", %{})
    index = Map.get(hit, "_index", "")

    source
    |> Map.put("id", Map.get(hit, "_id"))
    |> Map.put("index", index)
    |> Map.put("log_type", detect_log_type(index))
    |> Map.put("display_time", format_timestamp(Map.get(source, "@timestamp")))
    |> Map.put("score", Map.get(hit, "_score"))
  end

  def matches_exact_filters?(log, filters) do
    Enum.all?(["host", "program"], fn field ->
      expected = Map.get(filters, field, "")

      expected == "" or not is_nil(Query.regex_pattern(expected)) or
        Map.get(log, field, "") == expected
    end)
  end

  def detect_log_type(index_name) do
    cond do
      String.contains?(index_name, "authlog") -> "authlog"
      String.contains?(index_name, "syslog") -> "syslog"
      true -> "unknown"
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
    case DateTime.from_iso8601(String.replace(value, "Z", "+00:00")) do
      {:ok, datetime, _offset} ->
        datetime
        |> DateTime.add(@jst_offset_seconds, :second)
        |> Calendar.strftime("%Y/%m/%d %H:%M:%S JST")

      {:error, _reason} ->
        value
    end
  end

  def format_timestamp(value), do: to_string(value)
end
