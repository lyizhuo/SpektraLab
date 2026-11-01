#!/usr/bin/env python3
"""Talk to the Figma MCP server directly, using the OAuth token that
`figma_oauth.py` obtained.

    python figma_mcp.py tools
    python figma_mcp.py call <tool-name> '<json-args>'

Streamable HTTP transport: JSON-RPC over POST, replies either JSON or SSE.
The session id the server hands back on `initialize` is echoed on every later
request; without it the server starts a fresh session and the call fails with
"not initialized". The token is read from the file, never from argv, and is
never printed.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ENDPOINT = "https://mcp.figma.com/mcp"
PROTOCOL = "2025-06-18"
TOKEN = Path(os.environ.get("TEMP", "/tmp")) / "figma_token.json"
SESSION_FILE = Path(os.environ.get("TEMP", "/tmp")) / "figma_mcp_session.txt"


def load_token() -> str:
    if not TOKEN.exists():
        sys.exit(f"[error] no token at {TOKEN}; run figma_oauth.py first")
    payload = json.loads(TOKEN.read_text(encoding="utf-8"))
    token = payload.get("access_token")
    if not token:
        sys.exit("[error] token file has no access_token")
    return token


def post(body: dict, token: str, session: str | None, want_sse: bool = True) -> tuple[dict | None, str | None]:
    data = json.dumps(body).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL,
        "Accept-Encoding": "identity",
    }
    if session:
        headers["Mcp-Session-Id"] = session

    request = urllib.request.Request(ENDPOINT, data=data, headers=headers, method="POST")
    try:
        response = urllib.request.urlopen(request, timeout=300)
    except urllib.error.HTTPError as failure:
        detail = failure.read().decode("utf-8", "replace")[:600]
        sys.exit(f"[http {failure.code}] {detail}")

    with response:
        new_session = response.headers.get("Mcp-Session-Id")
        ctype = (response.headers.get("Content-Type") or "").lower()

        # A streamable-HTTP reply is often an SSE stream the server keeps open,
        # so reading to EOF blocks until it closes (or never). Read event by
        # event and stop at the message that answers *this* request.
        if "text/event-stream" in ctype:
            wanted = body.get("id")
            message = None
            deadline_lines = 200_000
            while deadline_lines > 0:
                deadline_lines -= 1
                try:
                    line = response.readline()
                except Exception:  # transport dropped; keep whatever we have
                    break
                if not line:
                    break
                text = line.decode("utf-8", "replace").strip()
                if not text.startswith("data:"):
                    continue
                chunk = text[5:].strip()
                if not chunk:
                    continue
                try:
                    candidate = json.loads(chunk)
                except json.JSONDecodeError:
                    continue
                if not isinstance(candidate, dict):
                    continue
                if candidate.get("id") == wanted:
                    message = candidate
                    break
                if wanted is None and "result" in candidate:
                    message = candidate
                    break
            return message, new_session

        raw = response.read().decode("utf-8", "replace")
        if not raw.strip():
            return None, new_session
        try:
            return json.loads(raw), new_session
        except json.JSONDecodeError:
            sys.exit(f"[error] reply was not JSON:\n{raw[:600]}")


def initialize(token: str) -> str | None:
    body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": PROTOCOL,
            "capabilities": {},
            "clientInfo": {"name": "spektralab-agent", "version": "0.1"},
        },
    }
    reply, session = post(body, token, None)
    if not reply or "result" not in reply:
        sys.exit(f"[error] initialize failed: {json.dumps(reply)[:600] if reply else 'empty'}")
    info = reply["result"].get("serverInfo", {})
    print(f"[mcp] connected to {info.get('name', '?')} {info.get('version', '')}, session={bool(session)}")
    if session:
        SESSION_FILE.write_text(session, encoding="utf-8")
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, token, session)
    return session


def main() -> int:
    token = load_token()
    if len(sys.argv) < 2:
        sys.exit(__doc__)

    command = sys.argv[1]
    session = initialize(token)

    if command == "tools":
        reply, _ = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, token, session)
        tools = (reply or {}).get("result", {}).get("tools", [])
        for tool in tools:
            described = (tool.get("description") or "").split("\n")[0][:90]
            print(f"  {tool['name']}: {described}")
        print(f"[mcp] {len(tools)} tool(s)")
        return 0

    if command == "call":
        if len(sys.argv) < 4:
            sys.exit("usage: figma_mcp.py call <tool> '<json>' [--out <path>]")
        arguments = json.loads(sys.argv[3])
        out_path = None
        if "--out" in sys.argv:
            out_path = Path(sys.argv[sys.argv.index("--out") + 1])
        reply, _ = post(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {"name": sys.argv[2], "arguments": arguments},
            },
            token,
            session,
        )
        # An image result arrives as base64 in the content list; write it out
        # and report the path instead of dumping a megabyte of base64.
        content = ((reply or {}).get("result") or {}).get("content") or []
        saved = []
        for index, item in enumerate(content):
            data = item.get("data")
            if item.get("type") != "image" or not data:
                continue
            suffix = (item.get("mimeType") or "image/png").split("/")[-1]
            out = Path(os.environ.get("TEMP", "/tmp")) / f"figma-{sys.argv[2]}-{index}.{suffix}"
            out.write_bytes(base64.b64decode(data))
            saved.append(str(out))
            item["data"] = f"<{len(data)} base64 chars written to {out}>"
        # A redirect merges stdout and stderr into one file and the reply stops
        # being JSON; --out keeps them apart and is what a caller should use.
        if out_path is not None:
            out_path.write_text(json.dumps(reply, ensure_ascii=False, indent=2), encoding="utf-8")
            content_items = ((reply or {}).get("result") or {}).get("content") or []
            chars = sum(len(item.get("text") or "") for item in content_items)
            print(f"[out] {out_path} ({chars} chars of text, {len(content_items)} item(s))")
            for path in saved:
                print(f"[saved] {path}")
            return 0
        print(json.dumps(reply, ensure_ascii=False, indent=2))
        for path in saved:
            print(f"[saved] {path}")
        return 0

    sys.exit(f"[error] unknown command {command}")


if __name__ == "__main__":
    sys.exit(main())
