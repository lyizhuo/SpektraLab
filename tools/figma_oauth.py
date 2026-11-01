#!/usr/bin/env python3
"""One-shot OAuth for the Figma MCP: authorize in a browser, catch the callback,
exchange the code, write the token to a file next to this script.

Stdlib only. Nothing is printed except the URL to open and a status word --
the client secret and the tokens never reach stdout.

    python figma_oauth.py
"""

import base64
import hashlib
import json
import os
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

CREDENTIALS = Path.home() / ".claude" / ".credentials.json"
ENTRY = "figma|d39d3b6252bc1ac5"
AUTHORIZE = "https://www.figma.com/oauth/mcp"
TOKEN = "https://api.figma.com/v1/oauth/token"
SCOPE = "mcp:connect"
TOKEN_OUT = Path(os.environ.get("TEMP", "/tmp")) / "figma_token.json"
PORT = 3118
WAIT_SECONDS = 900


def main() -> int:
    client = json.loads(CREDENTIALS.read_text(encoding="utf-8"))["mcpOAuth"][ENTRY]
    client_id = client["clientId"]
    client_secret = client["clientSecret"]
    redirect_uri = client["redirectUri"]

    expected_port = urllib.parse.urlparse(redirect_uri).port
    if expected_port != PORT:
        print(f"REDIRECT_PORT_MISMATCH expected={expected_port} script={PORT}", flush=True)
        return 5

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).decode().rstrip("=")
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()
    ).decode().rstrip("=")
    state = secrets.token_urlsafe(24)

    query = urllib.parse.urlencode(
        {
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "scope": SCOPE,
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        }
    )
    print("AUTHORIZE_URL=" + AUTHORIZE + "?" + query, flush=True)

    caught: dict[str, str] = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 - http.server's API
            parsed = urllib.parse.urlparse(self.path)
            params = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
            if not ("code" in params or "error" in params):
                self.send_response(404)
                self.end_headers()
                return
            caught.update(params)
            body = (
                "<html><meta charset='utf-8'><body style=\"font-family:sans-serif;"
                "background:#161616;color:#eee;display:flex;align-items:center;"
                "justify-content:center;height:100vh;margin:0\">"
                "<h2>Figma 授权完成，可以关掉这个页面。</h2></body></html>"
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    try:
        server = HTTPServer(("127.0.0.1", PORT), Handler)
    except OSError as failure:
        print(f"PORT_BUSY {PORT}: {failure}", flush=True)
        return 6

    threading.Thread(target=server.serve_forever, daemon=True).start()
    print("LISTENING", flush=True)

    deadline = time.time() + WAIT_SECONDS
    while time.time() < deadline and not caught:
        time.sleep(0.5)
    server.shutdown()

    if not caught:
        print("TIMEOUT", flush=True)
        return 2
    if caught.get("state") != state:
        print("STATE_MISMATCH", flush=True)
        return 3
    if "error" in caught:
        print("AUTH_ERROR " + caught["error"], flush=True)
        return 4

    form = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "code": caught["code"],
            "redirect_uri": redirect_uri,
            "client_id": client_id,
            "client_secret": client_secret,
            "code_verifier": verifier,
        }
    ).encode("ascii")

    request = urllib.request.Request(
        TOKEN,
        data=form,
        headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            payload = json.loads(response.read().decode("utf-8"))
            status = response.status
    except urllib.error.HTTPError as failure:
        detail = failure.read().decode("utf-8", "replace")[:300]
        print(f"TOKEN_HTTP_{failure.code} {detail}", flush=True)
        return 7

    if not payload.get("access_token"):
        print("TOKEN_MISSING", flush=True)
        return 8

    TOKEN_OUT.write_text(
        json.dumps(
            {
                "access_token": payload["access_token"],
                "refresh_token": payload.get("refresh_token"),
                "expires_in": payload.get("expires_in"),
                "scope": payload.get("scope"),
                "obtained_at": int(time.time()),
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"SAVED {TOKEN_OUT} status={status}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
