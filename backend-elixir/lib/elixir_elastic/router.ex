defmodule ElixirElastic.Router do
  @moduledoc false

  use Plug.Router

  alias ElixirElastic.ElasticSearch

  plug Plug.Parsers,
    parsers: [:urlencoded, :json],
    pass: ["application/json"],
    json_decoder: Jason

  plug :match
  plug :dispatch

  get "/" do
    json(conn, %{
      service: "elixir-elastic-backend",
      endpoints: ["/health", "/api/options", "/api/logs"]
    })
  end

  get "/health" do
    json(conn, %{
      ok: ElasticSearch.ping(),
      elasticsearch_url: Application.fetch_env!(:elixir_elastic, :elasticsearch_url),
      index: Application.fetch_env!(:elixir_elastic, :elasticsearch_index)
    })
  end

  get "/api/logs" do
    conn = fetch_query_params(conn)
    filters = normalize_filters(conn.query_params)
    search_json(conn, filters)
  end

  post "/api/logs" do
    filters = normalize_filters(conn.body_params)
    search_json(conn, filters)
  end

  get "/api/options" do
    json(conn, %{log_types: ElasticSearch.log_types()})
  end

  get "/api/log-types" do
    json(conn, %{log_types: ElasticSearch.log_types()})
  end

  match _ do
    send_resp(conn, 404, "Not found")
  end

  def normalize_filters(params) do
    %{
      "time_from" => clean(params["time_from"]),
      "time_to" => clean(params["time_to"]),
      "log_type" => clean(params["log_type"]),
      "host" => clean(params["host"]),
      "program" => clean(params["program"]),
      "message" => clean(params["message"])
    }
  end

  defp clean(nil), do: ""
  defp clean(value), do: String.trim(to_string(value))

  defp json(conn, payload) do
    json(conn, 200, payload)
  end

  defp json(conn, status, payload) do
    conn
    |> put_resp_content_type("application/json")
    |> send_resp(status, Jason.encode!(payload))
  end

  defp search_json(conn, filters) do
    try do
      logs = ElasticSearch.search_logs(filters)
      json(conn, %{filters: filters, count: length(logs), logs: logs})
    rescue
      error -> json(conn, 502, %{error: Exception.message(error)})
    end
  end
end
