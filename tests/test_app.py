import pytest

import app as log_app
import elasticsearch_logs as log_search


class FakeElasticsearch:
    def __init__(self):
        self.search_calls = []

    def ping(self):
        return True

    def search(self, **kwargs):
        self.search_calls.append(kwargs)
        return {
            "hits": {
                "hits": [
                    {
                        "_id": "1",
                        "_index": ".ds-logs-syslog-2026.06.02-000001",
                        "_score": 1.0,
                        "_source": {
                            "@timestamp": 1780398715000,
                            "host": "flink1",
                            "program": "systemd",
                            "msg": "Reached target sshd-keygen.target.",
                            "severity": 6,
                        },
                    }
                ]
            }
        }


@pytest.fixture()
def fake_client(monkeypatch):
    client = FakeElasticsearch()
    monkeypatch.setattr(log_app, "get_client", lambda: client)
    return client


@pytest.fixture()
def flask_client(fake_client):
    log_app.app.config.update(TESTING=True)
    return log_app.app.test_client()


def test_format_timestamp_converts_epoch_millis_to_jst():
    assert log_search.format_timestamp(1780398715000) == "2026/06/02 20:11:55 JST"


def test_datetime_local_to_iso_treats_input_as_jst():
    converted = log_search.datetime_local_to_iso("2026-06-02T20:11")
    assert converted == "2026-06-02T11:11:00+00:00"


def test_detect_log_type_from_index_name():
    assert log_search.detect_log_type(".ds-logs-syslog-2026.06.02-000001") == "syslog"
    assert log_search.detect_log_type(".ds-logs-authlog-2026.06.02-000001") == "authlog"
    assert log_search.detect_log_type("metrics-2026.06.02") == "unknown"


def test_build_query_with_message_program_host_and_time_range():
    filters = {
        "time_from": "2026-06-02T20:00",
        "time_to": "2026-06-02T21:00",
        "log_type": "syslog",
        "host": "flink1",
        "program": "systemd",
        "message": "sshd",
    }

    query = log_search.build_query(filters)

    assert query["bool"]["must"][0]["bool"]["should"][0] == {"match_phrase": {"msg": {"query": "sshd"}}}
    assert query["bool"]["must"][0]["bool"]["should"][1] == {"match": {"msg": {"query": "sshd", "operator": "and"}}}
    assert {
        "bool": {
            "should": [
                {"term": {"program.keyword": {"value": "systemd"}}},
                {"term": {"program": {"value": "systemd"}}},
            ],
            "minimum_should_match": 1,
        }
    } in query["bool"]["filter"]
    assert {
        "bool": {
            "should": [
                {"term": {"host.keyword": {"value": "flink1"}}},
                {"term": {"host": {"value": "flink1"}}},
            ],
            "minimum_should_match": 1,
        }
    } in query["bool"]["filter"]
    assert {
        "range": {
            "@timestamp": {
                "gte": "2026-06-02T11:00:00+00:00",
                "lte": "2026-06-02T12:00:00+00:00",
            }
        }
    } in query["bool"]["filter"]


def test_build_query_supports_space_separated_message_search():
    filters = log_search.normalize_filters({"message": "authlog forward test from"})

    query = log_search.build_query(filters)
    message_should = query["bool"]["must"][0]["bool"]["should"]

    assert {"match_phrase": {"msg": {"query": "authlog forward test from"}}} in message_should
    assert {"match": {"msg": {"query": "authlog forward test from", "operator": "and"}}} in message_should
    assert {"wildcard": {"msg": {"value": "*authlog forward test from*", "case_insensitive": True}}} in message_should
    assert {"wildcard": {"msg.keyword": {"value": "*authlog forward test from*", "case_insensitive": True}}} in message_should


def test_search_logs_uses_log_specific_index_and_formats_result(fake_client):
    filters = {
        "time_from": "",
        "time_to": "",
        "log_type": "syslog",
        "host": "",
        "program": "systemd",
        "message": "sshd",
    }

    logs = log_search.search_logs(fake_client, filters)

    search_call = fake_client.search_calls[0]
    assert search_call["index"] == "logs-syslog-*"
    assert search_call["size"] == 50
    assert logs[0]["display_time"] == "2026/06/02 20:11:55 JST"
    assert logs[0]["log_type"] == "syslog"


def test_search_logs_filters_host_and_program_exactly_after_search(fake_client):
    fake_client.search = lambda **kwargs: {
        "hits": {
            "hits": [
                {
                    "_id": "1",
                    "_index": ".ds-logs-syslog-2026.06.02-000001",
                    "_source": {
                        "@timestamp": 1780398715000,
                        "host": "flink1",
                        "program": "systemd",
                        "msg": "exact",
                    },
                },
                {
                    "_id": "2",
                    "_index": ".ds-logs-syslog-2026.06.02-000001",
                    "_source": {
                        "@timestamp": 1780398715000,
                        "host": "flink10",
                        "program": "systemd-logind",
                        "msg": "partial",
                    },
                },
            ]
        }
    }
    filters = log_search.normalize_filters({"host": "flink1", "program": "systemd"})

    logs = log_search.search_logs(fake_client, filters)

    assert [log["id"] for log in logs] == ["1"]


def test_api_root_describes_backend_service(flask_client):
    response = flask_client.get("/")

    assert response.status_code == 200
    payload = response.get_json()
    assert payload["service"] == "flask-elastic-backend"
    assert "/api/logs" in payload["endpoints"]


def test_api_options_returns_filter_options(flask_client):
    response = flask_client.get("/api/options")

    assert response.status_code == 200
    assert response.get_json() == {"log_types": ["syslog", "authlog"]}


def test_post_api_logs_accepts_json(flask_client):
    response = flask_client.post("/api/logs", json={"program": "systemd", "message": "sshd"})

    assert response.status_code == 200
    payload = response.get_json()
    assert payload["count"] == 1
    assert payload["filters"]["program"] == "systemd"
    assert payload["logs"][0]["display_time"] == "2026/06/02 20:11:55 JST"


def test_post_api_logs_with_empty_filters_searches_all_logs(flask_client, fake_client):
    response = flask_client.post("/api/logs", json={})

    assert response.status_code == 200
    payload = response.get_json()
    assert payload["count"] == 1
    assert payload["logs"][0]["display_time"] == "2026/06/02 20:11:55 JST"
    assert fake_client.search_calls[-1]["query"] == {"match_all": {}}
