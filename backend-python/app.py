from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from elasticsearch_logs import get_client, get_filter_options, get_health, normalize_filters, search_logs

app = FastAPI(title="Python Elastic Backend")


def positive_int(value, default, maximum=None):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    parsed = max(1, parsed)
    return min(parsed, maximum) if maximum else parsed


def is_json_request(request):
    return request.headers.get("content-type", "").split(";", 1)[0].strip() == "application/json"


async def json_body(request):
    try:
        payload = await request.json()
    except ValueError:
        return {}
    return payload if isinstance(payload, dict) else {}


async def form_body(request):
    return await request.form()


async def request_values(request):
    if is_json_request(request):
        payload = await json_body(request)
        return payload, payload
    if request.method == "POST":
        values = await form_body(request)
        return values, values
    return request.query_params, request.query_params


@app.get("/")
def api_root():
    return {
        "service": "fastapi-elastic-backend",
        "endpoints": ["/health", "/api/options", "/api/logs"],
        "features": ["pagination", "url-filters", "log-detail"],
    }


@app.get("/health")
def health():
    client = get_client()
    return get_health(client)


@app.get("/api/options")
def api_options():
    return get_filter_options()


@app.api_route("/api/logs", methods=["GET", "POST"])
async def api_search_logs(request: Request):
    filters_source, values = await request_values(request)
    filters = normalize_filters(filters_source)
    page = positive_int(values.get("page"), 1)
    size = positive_int(values.get("size"), 20, 100)
    try:
        result = search_logs(get_client(), filters, page=page, size=size)
    except Exception as error:
        return JSONResponse({"error": str(error)}, status_code=502)
    results = result["results"]
    return {
        "filters": filters,
        **result,
        "count": len(results),
        "logs": results,
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=5000)
