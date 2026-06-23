import json
import os
import socketserver
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

from access_logs import load_logs, parse_date, parse_tail


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self.send_json(200, {"status": "ok"})
            return
        if parsed.path != "/logs":
            self.send_json(404, {"error": "not found"})
            return

        query = parse_qs(parsed.query)
        try:
            date = parse_date((query.get("date") or [None])[0])
        except ValueError:
            self.send_json(400, {"error": "invalid date, expected YYYY-MM-DD"})
            return

        full = (query.get("full") or [""])[0] == "1"
        tail = None if full else parse_tail((query.get("tail") or [None])[0])
        try:
            logs = load_logs(date, tail)
        except OSError as error:
            self.send_json(503, {"status": "error", "error": str(error)})
            return

        self.send_json(
            200,
            {
                "status": "ok",
                "service": "frontend",
                "date": date,
                "count": len(logs),
                "logs": logs,
            },
        )

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_, *args):
        print(format_ % args, flush=True)


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8090"))
    with Server(("0.0.0.0", port), Handler) as server:
        server.serve_forever()
