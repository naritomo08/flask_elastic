from flask import Flask, jsonify, request

from elasticsearch_logs import get_client, get_filter_options, get_health, normalize_filters, search_logs

app = Flask(__name__)


def positive_int(value, default, maximum=None):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    parsed = max(1, parsed)
    return min(parsed, maximum) if maximum else parsed


def filters_from_request():
    if request.is_json:
        return normalize_filters(request.get_json(silent=True) or {})
    if request.method == "POST":
        return normalize_filters(request.form)
    return normalize_filters(request.args)


@app.get("/")
def api_root():
    return jsonify(
        {
            "service": "flask-elastic-backend",
            "endpoints": ["/health", "/api/options", "/api/logs"],
            "features": ["pagination", "url-filters", "log-detail"],
        }
    )


@app.get("/health")
def health():
    client = get_client()
    return jsonify(get_health(client))


@app.get("/api/options")
def api_options():
    return jsonify(get_filter_options())


@app.route("/api/logs", methods=["GET", "POST"])
def api_search_logs():
    filters = filters_from_request()
    payload = request.get_json(silent=True) if request.is_json else None
    values = payload or request.values
    page = positive_int(values.get("page"), 1)
    size = positive_int(values.get("size"), 20, 100)
    try:
        result = search_logs(get_client(), filters, page=page, size=size)
    except Exception as error:
        return jsonify({"error": str(error)}), 502
    results = result["results"]
    return jsonify(
        {
            "filters": filters,
            **result,
            "count": len(results),
            "logs": results,
        }
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
