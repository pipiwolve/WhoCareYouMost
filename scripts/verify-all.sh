#!/usr/bin/env bash

set -euo pipefail

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 1
  fi
}

require_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "Missing required command: $name" >&2
    exit 1
  fi
}

check_java() {
  local raw
  raw="$(java -version 2>&1 | head -n 1)"
  local major
  major="$(printf '%s' "$raw" | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [[ -z "$major" || "$major" -lt 21 ]]; then
    echo "Java 21+ is required. Current: $raw" >&2
    exit 1
  fi
}

run_step() {
  local name="$1"
  shift
  local log_file="$LOG_DIR/${name}.log"
  printf '[%s] RUN  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$name" | tee -a "$SUMMARY_FILE"
  if "$@" >"$log_file" 2>&1; then
    printf '[%s] PASS %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$name" | tee -a "$SUMMARY_FILE"
  else
    local status=$?
    printf '[%s] FAIL %s (exit=%s)\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$name" "$status" | tee -a "$SUMMARY_FILE"
    printf 'Log: %s\n' "$log_file" | tee -a "$SUMMARY_FILE"
    exit "$status"
  fi
}

require_env "DASHSCOPE_API_KEY"
require_env "AMAP_MAPS_API_KEY"
require_command "node"
require_command "npx"
require_command "java"
check_java

TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
LOG_DIR="target/verification-logs/${TIMESTAMP}"
SUMMARY_FILE="${LOG_DIR}/summary.txt"
mkdir -p "$LOG_DIR"

{
  echo "MedicalAgent verification summary"
  echo "timestamp=${TIMESTAMP}"
  echo "java=$(java -version 2>&1 | head -n 1)"
  echo "node=$(node --version)"
  echo "npx=$(npx --version)"
  echo
} >"$SUMMARY_FILE"

run_step "compile" ./mvnw -B -ntp -DskipTests compile
run_step "unit-test" ./mvnw -B -ntp test
run_step "local-it" ./mvnw -B -ntp verify -Plocal-it
run_step "live-it" ./mvnw -B -ntp verify -Plive-it -DDASHSCOPE_API_KEY="$DASHSCOPE_API_KEY" -Damap.mcp.command=npx -Damap.mcp.api-key="$AMAP_MAPS_API_KEY"

printf '[%s] DONE all checks passed\n' "$(date '+%Y-%m-%d %H:%M:%S')" | tee -a "$SUMMARY_FILE"
