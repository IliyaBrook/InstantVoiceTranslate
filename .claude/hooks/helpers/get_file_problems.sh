#!/bin/bash
# MCP get_file_problems — wrapper for get_file_problems.py
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/get_file_problems.py" "$@"
