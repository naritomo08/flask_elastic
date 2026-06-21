from datetime import datetime, timezone

from log_format import JST


def datetime_local_to_iso(value):
    if not value:
        return ""
    try:
        return datetime.fromisoformat(value).replace(tzinfo=JST).astimezone(timezone.utc).isoformat()
    except ValueError:
        return value


def wildcard_value(value):
    escaped = value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
    return f"*{escaped}*"


def text_search_clause(field, value):
    return {
        "bool": {
            "should": [
                {"match_phrase": {field: {"query": value}}},
                {"match": {field: {"query": value, "operator": "and"}}},
                {"wildcard": {field: {"value": wildcard_value(value), "case_insensitive": True}}},
                {"wildcard": {f"{field}.keyword": {"value": wildcard_value(value), "case_insensitive": True}}},
            ],
            "minimum_should_match": 1,
        }
    }


def exact_match_clause(field, value):
    return {
        "bool": {
            "should": [
                {"term": {f"{field}.keyword": {"value": value}}},
                {"term": {field: {"value": value}}},
            ],
            "minimum_should_match": 1,
        }
    }


def regex_pattern(value):
    if len(value) >= 2 and value.startswith("/") and value.endswith("/"):
        return value[1:-1]
    return None


def exact_or_regex_clause(field, value):
    pattern = regex_pattern(value)
    if pattern is None:
        return exact_match_clause(field, value)
    return {
        "bool": {
            "should": [
                {"regexp": {f"{field}.keyword": {"value": pattern, "case_insensitive": True}}},
                {"regexp": {field: {"value": pattern, "case_insensitive": True}}},
            ],
            "minimum_should_match": 1,
        }
    }


def build_query(filters):
    must = []
    filter_clauses = []

    if filters["message"]:
        must.append(text_search_clause("msg", filters["message"]))
    if filters["program"]:
        filter_clauses.append(exact_or_regex_clause("program", filters["program"]))
    if filters["host"]:
        filter_clauses.append(exact_or_regex_clause("host", filters["host"]))

    time_range = {}
    if filters["time_from"]:
        time_range["gte"] = datetime_local_to_iso(filters["time_from"])
    if filters["time_to"]:
        time_range["lte"] = datetime_local_to_iso(filters["time_to"])
    if time_range:
        filter_clauses.append({"range": {"@timestamp": time_range}})

    if not must and not filter_clauses:
        return {"match_all": {}}

    query = {"bool": {}}
    if must:
        query["bool"]["must"] = must
    if filter_clauses:
        query["bool"]["filter"] = filter_clauses
    return query
