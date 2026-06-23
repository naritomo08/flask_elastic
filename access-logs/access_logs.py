import json
import os
import re
from collections import deque
from datetime import datetime, timedelta, timezone


ACCESS_LOG_DIR = os.getenv("ACCESS_LOG_DIR", "/var/log/elastic-access")
JST = timezone(timedelta(hours=9))
TAIL_DEFAULT = 200
TAIL_MAX = 1000
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def today_jst():
    return datetime.now(JST).date().isoformat()


def parse_date(value):
    if value in (None, ""):
        return today_jst()
    if not DATE_RE.match(value):
        raise ValueError("invalid date")
    datetime.strptime(value, "%Y-%m-%d")
    return value


def parse_tail(value):
    if value is None or not value.isdigit():
        return TAIL_DEFAULT
    return min(max(int(value), 1), TAIL_MAX)


def read_lines(date, tail=None):
    path = os.path.join(ACCESS_LOG_DIR, f"access-{date}.jsonl")
    try:
        with open(path, encoding="utf-8") as handle:
            if tail is None:
                return [line.rstrip("\n") for line in handle]
            return list(deque((line.rstrip("\n") for line in handle), maxlen=tail))
    except FileNotFoundError:
        return []


def parse_line(line):
    try:
        return json.loads(line)
    except (TypeError, ValueError):
        return None


def timestamp_date(timestamp):
    try:
        return datetime.fromisoformat(timestamp).astimezone(JST).date().isoformat()
    except (TypeError, ValueError):
        return str(timestamp or "")[:10]


def load_logs(date, tail=None):
    logs = []
    for line in read_lines(date, tail):
        entry = parse_line(line)
        if entry is None or timestamp_date(entry.get("time")) != date:
            continue
        timestamp = entry.pop("time", None)
        logs.append(
            {
                "@timestamp": timestamp,
                "dt": date,
                "service": "frontend",
                "container": os.getenv("FRONTEND_CONTAINER_NAME", "elastic-search-frontend"),
                **entry,
            }
        )
    return logs
