from flask import Flask, jsonify, request

from elasticsearch_logs import get_client, get_filter_options, get_health, normalize_filters, search_logs

app = Flask(__name__)


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
    logs = search_logs(get_client(), filters)
    return jsonify({"filters": filters, "count": len(logs), "logs": logs})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
