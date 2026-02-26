#!/usr/bin/env bash
#
# UserPromptSubmit hook: reset tracked files list on each new prompt
#
# Clears edited-files.txt so each prompt starts fresh tracking

set -uo pipefail

# ── 1. Terminal guard ─────────────────────────────────────────────
if [[ -t 0 ]]; then
    exit 0
fi

# ── 2. Consume stdin (required even if unused) ───────────────────
cat > /dev/null

# ── 3. Clear tracking file ───────────────────────────────────────
data_dir="${CLAUDE_PROJECT_DIR:-.}/.claude/hooks/data"
tracking_file="$data_dir/edited-files.txt"

mkdir -p "$data_dir"
> "$tracking_file"

exit 0
