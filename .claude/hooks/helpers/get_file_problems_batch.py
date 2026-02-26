#!/usr/bin/env python3
"""
Batch JetBrains MCP get_file_problems — checks multiple files in one SSE session.

Usage:
  ./get_file_problems_batch.py <file_list_path> [per_file_timeout_ms]

  file_list_path:       text file with one absolute file path per line
  per_file_timeout_ms:  timeout per file in ms (default: 15000)

Output: JSON array of {filePath, errors} for files that have problems.
Prints "[]" and exits 0 if no problems or JetBrains is unreachable.
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

    port_files = glob.glob(os.path.expanduser("~/.cache/JetBrains/Toolbox/ports/*.port"))
    port_files += glob.glob(os.path.expanduser("~/.cache/JetBrains/*/.port"))
    for pf in port_files:
        try:
            port = int(open(pf).read().strip())
            candidates.append(port + 1000)
        except (ValueError, OSError):
            pass

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

    seen: set[int] = set()
    unique: list[int] = []
    for p in candidates:
        if p not in seen:
            seen.add(p)
            unique.append(p)

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

    return "http://localhost:64342"


# ── Args ──────────────────────────────────────────────────────────
FILE_LIST_PATH = sys.argv[1] if len(sys.argv) > 1 else None
PER_FILE_TIMEOUT_MS = int(sys.argv[2]) if len(sys.argv) > 2 else 15000

CONNECT_DEADLINE_SEC = 30  # total time budget for MCP connection (all retries)
RETRY_DELAY_SEC = 2

response_queue: Queue = Queue()
endpoint: str | None = None
endpoint_event = threading.Event()
stop_event = threading.Event()
mcp_url: str = ""


def listen_sse():
    """Keep SSE connection open, parse events and put responses into the queue."""
    global endpoint
    req = urllib.request.Request(
        f"{mcp_url}/sse",
        headers={"Accept": "text/event-stream", "Cache-Control": "no-cache"},
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
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
        endpoint_event.set()


def reset_connection_state():
    """Reset global state for a fresh connection attempt."""
    global endpoint
    endpoint = None
    endpoint_event.clear()
    stop_event.clear()
    # Drain the queue
    while not response_queue.empty():
        try:
            response_queue.get_nowait()
        except Empty:
            break


def connect_mcp(remaining_sec: float) -> bool:
    """
    Detect MCP URL, start SSE listener, and initialize the MCP session.
    Uses at most `remaining_sec` for this attempt.
    Returns True if connection + init succeeded.
    """
    global mcp_url
    reset_connection_state()

    # Split remaining time: ~40% for detect+SSE, ~40% for init, ~20% buffer
    sse_timeout = max(remaining_sec * 0.4, 2)
    init_timeout = max(remaining_sec * 0.4, 2)

    mcp_url = os.environ.get("MCP_URL") or detect_mcp_url()

    sse_thread = threading.Thread(target=listen_sse, daemon=True)
    sse_thread.start()

    if not endpoint_event.wait(timeout=sse_timeout):
        stop_event.set()
        return False

    if endpoint is None:
        stop_event.set()
        return False

    ok = post_message({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "claude-hook-batch", "version": "1.0"},
        },
    })
    if not ok:
        stop_event.set()
        return False

    init_resp = wait_for_response(expected_id=1, timeout_sec=init_timeout)
    if init_resp is None:
        stop_event.set()
        return False

    post_message({
        "jsonrpc": "2.0",
        "method": "notifications/initialized",
        "params": {},
    })
    return True


def post_message(payload: dict) -> bool:
    """Send a JSON-RPC request via POST."""
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{mcp_url}{endpoint}",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status in (200, 202)
    except Exception as e:
        print(f"[POST error] {e}", file=sys.stderr)
        return False


def wait_for_response(expected_id: int, timeout_sec: float = 20.0) -> dict | None:
    """Wait for a JSON-RPC response with the given id."""
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            msg = response_queue.get(timeout=0.5)
            if msg.get("id") == expected_id:
                return msg
        except Empty:
            continue
    return None


def extract_problems(result: dict) -> dict | None:
    """Extract {filePath, errors} from an MCP tools/call result."""
    structured = result.get("structuredContent")
    if not structured:
        content = result.get("content", [])
        if content and content[0].get("text"):
            try:
                structured = json.loads(content[0]["text"])
            except (json.JSONDecodeError, KeyError, IndexError):
                return None
    if structured and structured.get("errors"):
        return {
            "filePath": structured.get("filePath", ""),
            "errors": structured["errors"],
        }
    return None


def main():
    if not FILE_LIST_PATH or not os.path.isfile(FILE_LIST_PATH):
        print("[]")
        return

    with open(FILE_LIST_PATH) as f:
        file_paths = [line.strip() for line in f if line.strip()]

    # Filter to existing files only
    file_paths = [p for p in file_paths if os.path.exists(p)]

    if not file_paths:
        print("[]")
        return

    # ── Connect with retries (30s deadline) ─────────────────────────
    deadline = time.time() + CONNECT_DEADLINE_SEC
    connected = False
    attempt = 0

    while time.time() < deadline:
        attempt += 1
        remaining = deadline - time.time()
        if remaining <= 1:
            break

        if connect_mcp(remaining_sec=remaining):
            connected = True
            break

        print(f"[retry] MCP connection attempt {attempt} failed, {remaining:.0f}s left", file=sys.stderr)
        sleep_time = min(RETRY_DELAY_SEC, deadline - time.time())
        if sleep_time > 0:
            time.sleep(sleep_time)

    if not connected:
        print("[]")
        return

    # ── Process each file ─────────────────────────────────────────
    results: list[dict] = []
    request_id = 2
    per_file_timeout_sec = PER_FILE_TIMEOUT_MS / 1000 + 5

    for file_path in file_paths:
        ok = post_message({
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "tools/call",
            "params": {
                "name": "get_file_problems",
                "arguments": {
                    "filePath": file_path,
                    "errorsOnly": False,
                    "timeout": PER_FILE_TIMEOUT_MS,
                },
            },
        })

        if ok:
            resp = wait_for_response(
                expected_id=request_id,
                timeout_sec=per_file_timeout_sec,
            )
            if resp and resp.get("result"):
                problems = extract_problems(resp["result"])
                if problems:
                    results.append(problems)

        request_id += 1

    stop_event.set()
    print(json.dumps(results, ensure_ascii=False))


if __name__ == "__main__":
    main()
