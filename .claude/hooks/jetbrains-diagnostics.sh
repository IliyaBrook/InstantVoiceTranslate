#!/usr/bin/env bash
#
# Stop hook: JetBrains IDE diagnostics for all edited files
#
# Trigger: Stop — fires when Claude finishes its response
# Reads edited-files.txt, runs JetBrains get_file_problems for each file,
# and reports ALL warnings (including WEAK WARNING) back to Claude for fixing.

set -uo pipefail

# ── 1. Terminal guard ─────────────────────────────────────────────
if [[ -t 0 ]]; then
    exit 0
fi

# ── 2. Consume stdin ─────────────────────────────────────────────
cat > /dev/null

# ── 3. Paths ──────────────────────────────────────────────────────
project_dir="${CLAUDE_PROJECT_DIR:-.}"
data_dir="$project_dir/.claude/hooks/data"
tracking_file="$data_dir/edited-files.txt"
helpers_dir="$project_dir/.claude/hooks/helpers"
batch_script="$helpers_dir/get_file_problems_batch.py"

# ── 4. Skip if no tracked files ──────────────────────────────────
[ ! -s "$tracking_file" ] && exit 0

# ── 5. Run batch diagnostics ─────────────────────────────────────
raw_output=$(python3 "$batch_script" "$tracking_file" 15000 2>/dev/null)

# ── 6. Skip if no output or empty array ──────────────────────────
[ -z "$raw_output" ] && exit 0
[ "$raw_output" = "[]" ] && exit 0

# ── 7. Count total problems ──────────────────────────────────────
problem_count=$(printf '%s' "$raw_output" | jq '[.[].errors | length] | add // 0' 2>/dev/null)
[ "${problem_count:-0}" -eq 0 ] && exit 0

# ── 8. Format feedback message ───────────────────────────────────
message="JetBrains IDE diagnostics found ${problem_count} problem(s) in files you edited this session."
message+=$'\n\n'

while IFS= read -r file_entry; do
    file_path=$(printf '%s' "$file_entry" | jq -r '.filePath // empty')
    [ -z "$file_path" ] && continue

    error_count=$(printf '%s' "$file_entry" | jq '.errors | length' 2>/dev/null)
    message+="## ${file_path} (${error_count} issue(s))"
    message+=$'\n'

    while IFS= read -r error_item; do
        severity=$(printf '%s' "$error_item" | jq -r '.severity // empty')
        description=$(printf '%s' "$error_item" | jq -r '.description // empty')
        line_num=$(printf '%s' "$error_item" | jq -r '.line // empty')
        col=$(printf '%s' "$error_item" | jq -r '.column // empty')
        line_content=$(printf '%s' "$error_item" | jq -r '.lineContent // empty')

        message+="- [${severity}] line ${line_num}, col ${col}: ${description}"
        message+=$'\n'

        if [ -n "$line_content" ]; then
            message+="  Code: \`${line_content}\`"
            message+=$'\n'
        fi

        if [[ "$description" == *"Duplicated code fragment"* ]]; then
            message+="  -> Use Context7 MCP tool to look up best practices for deduplicating this code pattern"
            message+=$'\n'
        fi
    done < <(printf '%s' "$file_entry" | jq -c '.errors[]' 2>/dev/null)

    message+=$'\n'
done < <(printf '%s' "$raw_output" | jq -c '.[]' 2>/dev/null)

message+="IMPORTANT: Fix ALL warnings listed above, including WEAK WARNINGs."
message+=$'\n'
message+="Even if a warning is not directly related to your changes, fix it if the fix is safe and will not break existing functionality."
message+=$'\n'
message+="For 'Duplicated code fragment' warnings, use the Context7 MCP tool to find proper abstractions or shared utilities."

# ── 9. Output JSON feedback ──────────────────────────────────────
printf '%s\n' "$message" | jq -Rs '{"decision": "block", "reason": .}'
exit 0
