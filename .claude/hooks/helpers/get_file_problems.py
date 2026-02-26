#!/usr/bin/env python3
"""
MCP get_file_problems tester (JetBrains MCP server).

Usage:
  ./get_file_problems.py [file_path] [errors_only] [timeout_ms] [project_path]
"""

import glob
import os
import re
import subprocess
import sys
import json
import threading
import time
import urllib.request
import urllib.error
from queue import Queue, Empty


def detect_mcp_url() -> str:
    """
    Detect the JetBrains MCP server URL by:
    1. Reading built-in web server ports from ~/.cache/JetBrains/Toolbox/ports/*.port
       and trying port + 1000 (known MCP offset).
    2. Falling back to scanning all ports that JetBrains processes listen on via `ss`.
    3. For each candidate port, probe GET /sse to confirm it's an MCP server.
    """
    candidates: list[int] = []

    # Strategy 1: port files → MCP port is built-in + 1000
    port_files = glob.glob(os.path.expanduser("~/.cache/JetBrains/Toolbox/ports/*.port"))
    port_files += glob.glob(os.path.expanduser("~/.cache/JetBrains/*/.port"))
    for pf in port_files:
        try:
            port = int(open(pf).read().strip())
            candidates.append(port + 1000)
        except (ValueError, OSError):
            pass

    # Strategy 2: ss -tlnp — ports held by JetBrains processes
    try:
        out = subprocess.check_output(["ss", "-tlnp"], text=True, stderr=subprocess.DEVNULL)
        jetbrains_keywords = ("idea", "pycharm", "webstorm", "clion", "goland", "rider", "phpstorm")
        for line in out.splitlines():
            if any(kw in line.lower() for kw in jetbrains_keywords):
                m = re.search(r":(\d+)\s", line)
                if m:
                    candidates.append(int(m.group(1)))
    except (subprocess.SubprocessError, FileNotFoundError):
        pass

    # Deduplicate, preserving order
    seen: set[int] = set()
    unique: list[int] = []
    for p in candidates:
        if p not in seen:
            seen.add(p)
            unique.append(p)

    # Probe each candidate
    for port in unique:
        url = f"http://localhost:{port}"
        try:
            req = urllib.request.Request(
                f"{url}/sse",
                headers={"Accept": "text/event-stream"},
            )
            with urllib.request.urlopen(req, timeout=2) as resp:
                chunk = resp.read(64).decode("utf-8", errors="ignore")
                if "/message" in chunk or "endpoint" in chunk:
                    return url
        except Exception:
            pass

    return "http://localhost:64342"  # last-resort default


MCP_URL = os.environ.get("MCP_URL") or detect_mcp_url()

FILE_PATH = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("FILE_PATH")
ERRORS_ONLY = sys.argv[2].lower() == "true" if len(sys.argv) > 2 else False
TIMEOUT_MS = int(sys.argv[3]) if len(sys.argv) > 3 else 30000
PROJECT_PATH = sys.argv[4] if len(sys.argv) > 4 else None

response_queue: Queue = Queue()
endpoint: str | None = None
endpoint_event = threading.Event()
stop_event = threading.Event()


def listen_sse():
    """Keep SSE connection open, parse events and put responses into the queue."""
    global endpoint
    req = urllib.request.Request(
        f"{MCP_URL}/sse",
        headers={"Accept": "text/event-stream", "Cache-Control": "no-cache"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_MS / 1000 + 10) as resp:
            current_data = None
            for raw_line in resp:
                if stop_event.is_set():
                    break
                line = raw_line.decode("utf-8").rstrip("\r\n")
                if line.startswith("data: "):
                    current_data = line[6:]
                elif line == "" and current_data is not None:
                    data = current_data.strip()
                    current_data = None
                    if data.startswith("/"):
                        # Message endpoint
                        endpoint = data
                        endpoint_event.set()
                    else:
                        try:
                            msg = json.loads(data)
                            response_queue.put(msg)
                        except json.JSONDecodeError:
                            pass
    except Exception as e:
        if not stop_event.is_set():
            print(f"[SSE error] {e}", file=sys.stderr)
        endpoint_event.set()  # Unblock waiting if stuck


def post_message(payload: dict) -> bool:
    """Send a JSON-RPC request via POST. Response will come back over SSE."""
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{MCP_URL}{endpoint}",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            status = resp.status
            if status not in (200, 202):
                print(f"[POST] Unexpected status: {status}", file=sys.stderr)
                return False
            return True
    except urllib.error.HTTPError as e:
        print(f"[POST] HTTP error: {e.code} {e.reason}", file=sys.stderr)
        return False
    except Exception as e:
        print(f"[POST] Error: {e}", file=sys.stderr)
        return False


def wait_for_response(expected_id: int, timeout_sec: float = 15.0) -> dict | None:
    """Wait for a JSON-RPC response with the given id from the SSE queue."""
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            msg = response_queue.get(timeout=0.5)
            if msg.get("id") == expected_id:
                return msg
        except Empty:
            continue
    return None


def main():
    if not FILE_PATH:
        print("Error: file path required (arg or FILE_PATH env)", file=sys.stderr)
        sys.exit(1)

    sse_thread = threading.Thread(target=listen_sse, daemon=True)
    sse_thread.start()

    if not endpoint_event.wait(timeout=10):
        sys.exit(1)

    if endpoint is None:
        sys.exit(1)

    # 1. Initialize
    ok = post_message({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "test-client", "version": "1.0"},
        },
    })
    if not ok:
        sys.exit(1)

    wait_for_response(expected_id=1, timeout_sec=10)

    # 2. Initialized notification (required by protocol)
    post_message({
        "jsonrpc": "2.0",
        "method": "notifications/initialized",
        "params": {},
    })

    # 3. tools/call → get_file_problems
    args: dict = {
        "filePath": FILE_PATH,
        "errorsOnly": ERRORS_ONLY,
        "timeout": TIMEOUT_MS,
    }
    if PROJECT_PATH:
        args["projectPath"] = PROJECT_PATH

    ok = post_message({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/call",
        "params": {
            "name": "get_file_problems",
            "arguments": args,
        },
    })
    if not ok:
        sys.exit(1)

    tool_resp = wait_for_response(expected_id=2, timeout_sec=TIMEOUT_MS / 1000 + 5)

    if tool_resp is None:
        sys.exit(1)

    print(json.dumps(tool_resp, indent=2, ensure_ascii=False))

    stop_event.set()


if __name__ == "__main__":
    main()
