#!/usr/bin/env bash


# Run with: source scripts/cleanup_legacy_amap_env.sh
# Using `source` is required so unsets affect current shell.

LEGACY_VARS=(
  SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_COMMAND
  SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ARGS_0
  SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ARGS_1
  SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ARGS_2
  SPRING_AI_MCP_CLIENT_STDIO_CONNECTIONS_AMAP_ENV_AMAP_MAPS_API_KEY
)

echo "[cleanup] removing legacy AMAP stdio env vars from current shell..."
for var in "${LEGACY_VARS[@]}"; do
  unset "$var" || true
  echo "  - unset $var"
done

echo "[detect] checking leftover legacy vars..."
leftover=0
for var in "${LEGACY_VARS[@]}"; do
  value="$(eval "printf '%s' \"\${$var-}\"")"
  if [[ -n "$value" ]]; then
    echo "  [FOUND] $var=$value"
    leftover=1
  fi
done

if [[ $leftover -eq 0 ]]; then
  echo "  (none)"
fi

echo "[note] also remove these vars from IntelliJ Run Configuration Environment if present."
