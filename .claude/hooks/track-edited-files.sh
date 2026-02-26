#!/usr/bin/env bash
#
# PostToolUse hook: track unique file paths edited by Claude Code
#
# Trigger: Edit|Write|MultiEdit — fires after each file change
# Appends full file path to edited-files.txt (no duplicates)

set -uo pipefail

# ── 1. Terminal guard ─────────────────────────────────────────────
if [[ -t 0 ]]; then
    exit 0
fi

# ── 2. Read stdin ONCE ────────────────────────────────────────────
input=$(cat)

# ── 3. Extract file path ─────────────────────────────────────────
file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)

# ── 4. Early exit ────────────────────────────────────────────────
[ -z "$file_path" ] && exit 0

# ── 5. Make path absolute ────────────────────────────────────────
[[ "$file_path" = /* ]] || file_path="$(pwd)/$file_path"

# ── 6. Tracking file ─────────────────────────────────────────────
data_dir="${CLAUDE_PROJECT_DIR:-.}/.claude/hooks/data"
tracking_file="$data_dir/edited-files.txt"

mkdir -p "$data_dir"
touch "$tracking_file"

# ── 7. Add path only if not already present ──────────────────────
if ! grep -qxF "$file_path" "$tracking_file" 2>/dev/null; then
    printf '%s\n' "$file_path" >> "$tracking_file"
fi

exit 0
