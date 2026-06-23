import importlib
import json
import sys

import pytest


@pytest.fixture
def access_logs_module(monkeypatch, tmp_path):
    monkeypatch.setenv("ACCESS_LOG_DIR", str(tmp_path))
    sys.path.insert(0, "access-logs")
    sys.modules.pop("access_logs", None)
    module = importlib.import_module("access_logs")
    yield module, tmp_path
    sys.path.remove("access-logs")


def test_parse_date_rejects_invalid_values(access_logs_module):
    module, _ = access_logs_module
    with pytest.raises(ValueError):
        module.parse_date("2026/06/23")
    with pytest.raises(ValueError):
        module.parse_date("2026-13-40")


def test_load_logs_reads_only_requested_date_and_enriches(access_logs_module):
    module, log_dir = access_logs_module
    path = log_dir / "access-2026-06-23.jsonl"
    path.write_text(
        "\n".join(
            [
                json.dumps({"time": "2026-06-23T12:00:00+09:00", "method": "GET", "uri": "/"}),
                "not-json",
                json.dumps({"time": "2026-06-22T23:59:59+09:00", "method": "GET", "uri": "/old"}),
            ]
        ),
        encoding="utf-8",
    )

    logs = module.load_logs("2026-06-23")

    assert len(logs) == 1
    assert logs[0]["@timestamp"] == "2026-06-23T12:00:00+09:00"
    assert logs[0]["dt"] == "2026-06-23"
    assert logs[0]["service"] == "frontend"
    assert logs[0]["uri"] == "/"
