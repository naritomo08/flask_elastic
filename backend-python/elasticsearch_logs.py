import os
import time

from elasticsearch import Elasticsearch
from elasticsearch.exceptions import ConnectionError as ElasticsearchConnectionError

from log_format import detect_log_type, format_timestamp, log_matches_exact_filters
from search_query import build_query, regex_pattern


INDEX_PATTERN = os.getenv("ELASTICSEARCH_INDEX", "logs-*")
ELASTICSEARCH_URL = os.getenv("ELASTICSEARCH_URL", "http://elastic1:9200")
LOG_TYPES = ["syslog", "authlog"]


def get_client():
    return Elasticsearch(ELASTICSEARCH_URL, request_timeout=3)


def wait_for_elasticsearch(client, retries=30, delay=2):
    for attempt in range(1, retries + 1):
        try:
            if client.ping():
                return
        except ElasticsearchConnectionError:
            pass

        if attempt == retries:
            raise RuntimeError("Elasticsearch is not available")
        time.sleep(delay)


def get_health(client):
    started_at = time.monotonic()
    try:
        info = client.info()
        body = getattr(info, "body", info)
        return {
            "ok": True,
            "status": "ok",
            "backend": "python",
            "elasticsearch_url": ELASTICSEARCH_URL,
            "index": INDEX_PATTERN,
            "latency_ms": round((time.monotonic() - started_at) * 1000),
            "cluster_name": body.get("cluster_name", ""),
            "version": body.get("version", {}).get("number", ""),
        }
    except Exception as error:
        return {
            "ok": False,
            "status": "error",
            "backend": "python",
            "elasticsearch_url": ELASTICSEARCH_URL,
            "index": INDEX_PATTERN,
            "latency_ms": round((time.monotonic() - started_at) * 1000),
            "error": str(error),
        }


def normalize_filters(args):
    return {
        "time_from": args.get("time_from", "").strip(),
        "time_to": args.get("time_to", "").strip(),
        "log_type": args.get("log_type", "").strip(),
        "host": args.get("host", "").strip(),
        "program": args.get("program", "").strip(),
        "message": args.get("message", "").strip(),
    }


def index_pattern_for_log_type(log_type):
    if log_type in LOG_TYPES:
        return f"logs-{log_type}-*"
    return INDEX_PATTERN


def get_filter_options():
    return {"log_types": LOG_TYPES}


def search_logs(client, filters, page=1, size=50):
    response = client.search(
        index=index_pattern_for_log_type(filters["log_type"]),
        query=build_query(filters),
        sort=[{"@timestamp": {"order": "desc", "unmapped_type": "date"}}],
        from_=(page - 1) * size,
        size=size,
        ignore_unavailable=True,
        track_total_hits=True,
        request_timeout=10,
        timeout="5s",
        source=["@timestamp", "host", "program", "msg", "severity", "dt", "hr"],
    )
    body = getattr(response, "body", response)

    logs = []
    for hit in body["hits"]["hits"]:
        source = hit["_source"]
        log = {
            **source,
            "id": hit["_id"],
            "index": hit["_index"],
            "log_type": detect_log_type(hit["_index"]),
            "display_time": format_timestamp(source.get("@timestamp")),
            "score": hit.get("_score"),
        }
        if log_matches_exact_filters(log, filters, regex_pattern):
            logs.append(log)
    total_value = body.get("hits", {}).get("total", 0)
    total = total_value.get("value", 0) if isinstance(total_value, dict) else total_value
    return {"total": int(total), "page": page, "size": size, "results": logs}
