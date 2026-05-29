#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VLLM_BASE_URL="${VLLM_BASE_URL:-http://localhost:8000/v1}"
VLLM_MODEL="${VLLM_MODEL:-ibm-granite/granite-4.1-3b}"
VLLM_API_KEY="${VLLM_API_KEY:-EMPTY}"
VLLM_API_TYPE="${VLLM_API_TYPE:-chat}"
VLLM_MAX_TOKENS="${VLLM_MAX_TOKENS:-1024}"
VLLM_SYSTEM_PROMPT="${VLLM_SYSTEM_PROMPT:-You are a careful assistant. Follow the user request exactly. Return complete, concise answers. Do not invent product names.}"
VLLM_TOKENIZER_PATH="${VLLM_TOKENIZER_PATH:-}"
LLM_BACKEND="${LLM_BACKEND:-vllm}"
VLLM_GRPC_HOST="${VLLM_GRPC_HOST:-localhost}"
VLLM_GRPC_PORT="${VLLM_GRPC_PORT:-50051}"
VLLM_GRPC_PLAINTEXT="${VLLM_GRPC_PLAINTEXT:-true}"
AGENT_WORKFLOW="${AGENT_WORKFLOW:-planner-executor}"
AGENT_TOOLS="${AGENT_TOOLS:-time.now,web.search}"
AGENT_PROMPT="${AGENT_PROMPT:-What is the leading cause of multiple myeloma.}"
AGENT_PROMPTS="${AGENT_PROMPTS:-}" # What is the population of Ghana?|Summarize the metallurgic process of 304 stainless steel.|What is plasma cell dyscrasia?|Explain susceptibility of cholangiocarcinoma
AGENT_PROMPTS_FILE="${AGENT_PROMPTS_FILE:-}"
LLM_THREADS="${LLM_THREADS:-8}"
LLM_QUEUE_SIZE="${LLM_QUEUE_SIZE:-32}"
LLM_TIMEOUT_SECONDS="${LLM_TIMEOUT_SECONDS:-120}"
TOOL_TIMEOUT_SECONDS="${TOOL_TIMEOUT_SECONDS:-60}"
WORKFLOW_TIMEOUT_SECONDS="${WORKFLOW_TIMEOUT_SECONDS:-240}"
AGENT_MAX_TOOLS="${AGENT_MAX_TOOLS:-2}"
AGENT_MAX_STEPS="${AGENT_MAX_STEPS:-4}"
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-1 2 4 8}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$1" >&2
    exit 1
  fi
}

check_vllm() {
  if [[ "$LLM_BACKEND" == "vllm-grpc" || "$LLM_BACKEND" == "vllm_grpc" || "$LLM_BACKEND" == "grpc" ]]; then
    printf 'Using vLLM gRPC endpoint: %s:%s\n' "$VLLM_GRPC_HOST" "$VLLM_GRPC_PORT"
    return
  fi

  local models_url="${VLLM_BASE_URL%/}/models"
  printf 'Checking vLLM endpoint: %s\n' "$models_url"
  curl --fail --silent --show-error \
    -H "Authorization: Bearer ${VLLM_API_KEY}" \
    "$models_url" >/dev/null
}

run_level() {
  local requests="$1"
  local output
  printf '\n=== vLLM concurrency: %s request(s), workflow=%s, threads=%s ===\n' \
    "$requests" "$AGENT_WORKFLOW" "$LLM_THREADS"

  output="$(
    cd "$ROOT_DIR"
    LLM_BACKEND="$LLM_BACKEND" \
    VLLM_BASE_URL="$VLLM_BASE_URL" \
    VLLM_MODEL="$VLLM_MODEL" \
    VLLM_API_KEY="$VLLM_API_KEY" \
    VLLM_API_TYPE="$VLLM_API_TYPE" \
    VLLM_MAX_TOKENS="$VLLM_MAX_TOKENS" \
    VLLM_SYSTEM_PROMPT="$VLLM_SYSTEM_PROMPT" \
    VLLM_TOKENIZER_PATH="$VLLM_TOKENIZER_PATH" \
    VLLM_GRPC_HOST="$VLLM_GRPC_HOST" \
    VLLM_GRPC_PORT="$VLLM_GRPC_PORT" \
    VLLM_GRPC_PLAINTEXT="$VLLM_GRPC_PLAINTEXT" \
    AGENT_REQUESTS="$requests" \
    AGENT_WORKFLOW="$AGENT_WORKFLOW" \
    AGENT_TOOLS="$AGENT_TOOLS" \
    AGENT_MAX_TOOLS="$AGENT_MAX_TOOLS" \
    AGENT_MAX_STEPS="$AGENT_MAX_STEPS" \
    AGENT_PROMPT="$AGENT_PROMPT" \
    AGENT_PROMPTS="$AGENT_PROMPTS" \
    AGENT_PROMPTS_FILE="$AGENT_PROMPTS_FILE" \
    LLM_THREADS="$LLM_THREADS" \
    LLM_QUEUE_SIZE="$LLM_QUEUE_SIZE" \
    LLM_TIMEOUT_SECONDS="$LLM_TIMEOUT_SECONDS" \
    TOOL_TIMEOUT_SECONDS="$TOOL_TIMEOUT_SECONDS" \
    WORKFLOW_TIMEOUT_SECONDS="$WORKFLOW_TIMEOUT_SECONDS" \
    ./gradlew --quiet run
  )"
  printf '%s\n' "$output"
  printf 'BENCH concurrency=%s %s\n' "$requests" "$(printf '%s\n' "$output" | awk '/^METRIC summary / { sub(/^METRIC summary /, ""); print; }' | tail -n 1)"
}

require_command ./gradlew
if [[ "$LLM_BACKEND" != "vllm-grpc" && "$LLM_BACKEND" != "vllm_grpc" && "$LLM_BACKEND" != "grpc" ]]; then
  require_command curl
fi
check_vllm

printf 'Using LLM backend: %s\n' "$LLM_BACKEND"
printf 'Using vLLM model: %s\n' "$VLLM_MODEL"
printf 'Using vLLM API type: %s\n' "$VLLM_API_TYPE"
printf 'Using vLLM max tokens: %s\n' "$VLLM_MAX_TOKENS"
if [[ "$LLM_BACKEND" == "vllm-grpc" || "$LLM_BACKEND" == "vllm_grpc" || "$LLM_BACKEND" == "grpc" ]]; then
  printf 'Using vLLM gRPC plaintext: %s\n' "$VLLM_GRPC_PLAINTEXT"
  if [[ -n "$VLLM_TOKENIZER_PATH" ]]; then
    printf 'Using vLLM tokenizer path: %s\n' "$VLLM_TOKENIZER_PATH"
  else
    printf 'Using vLLM tokenizer path: <GetTokenizer fallback>\n'
  fi
fi
printf 'Using agent tools: %s\n' "$AGENT_TOOLS"
printf 'Using agent max tools: %s\n' "$AGENT_MAX_TOOLS"
printf 'Using agent max steps: %s\n' "$AGENT_MAX_STEPS"
if [[ -n "$AGENT_PROMPTS_FILE" ]]; then
  printf 'Using agent prompts file: %s\n' "$AGENT_PROMPTS_FILE"
elif [[ -n "$AGENT_PROMPTS" ]]; then
  prompt_count="$(awk -F'|' '{ print NF }' <<< "$AGENT_PROMPTS")"
  printf 'Using agent prompt variants: %s\n' "$prompt_count"
else
  printf 'Using single agent prompt\n'
fi
printf 'Using tool timeout seconds: %s\n' "$TOOL_TIMEOUT_SECONDS"
printf 'Concurrency levels: %s\n' "$CONCURRENCY_LEVELS"

for level in $CONCURRENCY_LEVELS; do
  run_level "$level"
done
