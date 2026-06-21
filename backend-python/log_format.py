from datetime import datetime, timedelta, timezone


JST = timezone(timedelta(hours=9), "JST")


def format_timestamp(value):
    if value is None:
        return ""

    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value / 1000, tz=timezone.utc).astimezone(JST).strftime("%Y/%m/%d %H:%M:%S JST")

    if isinstance(value, str):
        normalized = value.replace("Z", "+00:00")
        try:
            parsed = datetime.fromisoformat(normalized)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return parsed.astimezone(JST).strftime("%Y/%m/%d %H:%M:%S JST")
        except ValueError:
            return value

    return str(value)


def detect_log_type(index_name):
    if "authlog" in index_name:
        return "authlog"
    if "syslog" in index_name:
        return "syslog"
    return "unknown"


def log_matches_exact_filters(log, filters, regex_pattern):
    for field in ("host", "program"):
        expected = filters[field]
        if expected and regex_pattern(expected) is None and log.get(field) != expected:
            return False
    return True
