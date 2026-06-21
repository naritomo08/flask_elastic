defmodule ElixirElastic.Query do
  @moduledoc false

  @jst_offset_seconds 9 * 60 * 60

  def build(filters) do
    must = append_match([], filters["message"], "msg")

    filter_clauses =
      []
      |> append_exact_or_regex_filter(filters["host"], "host")
      |> append_exact_or_regex_filter(filters["program"], "program")
      |> append_time_filter(filters["time_from"], filters["time_to"])

    case {must, filter_clauses} do
      {[], []} -> %{match_all: %{}}
      {[], filters} -> %{bool: %{filter: filters}}
      {must, []} -> %{bool: %{must: must}}
      {must, filters} -> %{bool: %{must: must, filter: filters}}
    end
  end

  def regex_pattern(value) when is_binary(value) do
    if String.length(value) >= 2 and String.starts_with?(value, "/") and
         String.ends_with?(value, "/") do
      String.slice(value, 1, String.length(value) - 2)
    end
  end

  def regex_pattern(_value), do: nil

  def datetime_local_to_iso(value) when value in ["", nil], do: ""

  def datetime_local_to_iso(value) do
    with {:ok, naive} <- NaiveDateTime.from_iso8601(add_seconds(value)),
         {:ok, utc} <-
           DateTime.from_naive(NaiveDateTime.add(naive, -@jst_offset_seconds, :second), "Etc/UTC") do
      DateTime.to_iso8601(utc)
    else
      _ -> value
    end
  end

  defp append_match(list, value, _field) when value in ["", nil], do: list
  defp append_match(list, value, field), do: list ++ [text_search_clause(field, value)]

  defp text_search_clause(field, value) do
    wildcard = "*#{wildcard_escape(value)}*"

    %{
      bool: %{
        should: [
          %{match: %{field => %{query: value, operator: "and"}}},
          %{match_phrase: %{field => %{query: value}}},
          %{wildcard: %{"#{field}.keyword" => %{value: wildcard, case_insensitive: true}}},
          %{wildcard: %{field => %{value: wildcard, case_insensitive: true}}}
        ],
        minimum_should_match: 1
      }
    }
  end

  defp append_exact_or_regex_filter(list, value, _field) when value in ["", nil], do: list

  defp append_exact_or_regex_filter(list, value, field) do
    pattern = regex_pattern(value)

    clause =
      if is_nil(pattern) do
        %{
          bool: %{
            should: [
              %{term: %{field => value}},
              %{term: %{"#{field}.keyword" => value}}
            ],
            minimum_should_match: 1
          }
        }
      else
        %{
          bool: %{
            should: [
              %{regexp: %{field => %{value: pattern, case_insensitive: true}}},
              %{regexp: %{"#{field}.keyword" => %{value: pattern, case_insensitive: true}}}
            ],
            minimum_should_match: 1
          }
        }
      end

    list ++ [clause]
  end

  defp append_time_filter(list, time_from, time_to) do
    range =
      %{}
      |> maybe_put("gte", datetime_local_to_iso(time_from))
      |> maybe_put("lte", datetime_local_to_iso(time_to))

    if range == %{}, do: list, else: list ++ [%{range: %{"@timestamp" => range}}]
  end

  defp maybe_put(map, _key, value) when value in ["", nil], do: map
  defp maybe_put(map, key, value), do: Map.put(map, key, value)

  defp wildcard_escape(value) do
    value
    |> String.replace("\\", "\\\\")
    |> String.replace("*", "\\*")
    |> String.replace("?", "\\?")
  end

  defp add_seconds(value), do: if(String.length(value) == 16, do: value <> ":00", else: value)
end
