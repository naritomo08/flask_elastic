import os

from flask import Flask, jsonify, redirect, render_template, request, session, url_for

from elasticsearch_logs import get_client, get_filter_options, get_health, normalize_filters, search_logs

app = Flask(__name__)
app.secret_key = os.getenv("FLASK_SECRET_KEY", "dev-secret-key")


def filters_from_request():
    if request.is_json:
        return normalize_filters(request.get_json(silent=True) or {})
    if request.method == "POST":
        return normalize_filters(request.form)
    return normalize_filters(request.args)


@app.route("/", methods=["GET", "POST"])
def index():
    if request.method == "POST":
        session["filters"] = filters_from_request()
        session["searched"] = True
        return redirect(url_for("index"))

    if request.args:
        filters = filters_from_request()
        searched = True
    else:
        searched = session.pop("searched", False)
        filters = normalize_filters(session.pop("filters", {})) if searched else normalize_filters({})

    logs = search_logs(get_client(), filters) if searched else []
    options = get_filter_options()
    return render_template(
        "index.html",
        filters=filters,
        logs=logs,
        options=options,
        searched=searched,
    )


@app.get("/clear")
def clear_filters():
    session.pop("filters", None)
    session.pop("searched", None)
    return redirect(url_for("index"))


@app.get("/health")
def health():
    client = get_client()
    return jsonify(get_health(client))


@app.route("/api/logs", methods=["GET", "POST"])
def api_search_logs():
    filters = filters_from_request()
    logs = search_logs(get_client(), filters)
    return jsonify({"filters": filters, "count": len(logs), "logs": logs})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
