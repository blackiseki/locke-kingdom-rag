#!/usr/bin/env bash
# 快速测试问答
#
# 用法：
#   ./scripts/test-chat.sh                      # 用默认问题，走同步接口
#   ./scripts/test-chat.sh "火花的属性是什么？"   # 指定问题
#   STREAM=true ./scripts/test-chat.sh "..."    # 走 SSE 流式接口
#   PROVIDER=tongyi ./scripts/test-chat.sh "..." # 指定 provider（空=服务端默认/自动降级）
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
QUESTION="${1:-洛克王国里，火系精灵有哪些？}"
STREAM="${STREAM:-false}"
PROVIDER="${PROVIDER:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$STREAM" = "true" ]; then
  ENDPOINT="${BASE_URL}/api/chat/stream"
else
  ENDPOINT="${BASE_URL}/api/chat"
fi

# 用 python 做 JSON 转义，避免中文引号把 payload 弄坏；没有 python 就退回朴素拼装
payload=$(QUESTION="$QUESTION" PROVIDER="$PROVIDER" python -c '
import json, os
body = {"question": os.environ["QUESTION"]}
provider = os.environ.get("PROVIDER", "")
if provider:
    body["provider"] = provider
print(json.dumps(body, ensure_ascii=False))
' 2>/dev/null || {
  if [ -n "$PROVIDER" ]; then
    printf '{"question":"%s","provider":"%s"}' "$QUESTION" "$PROVIDER"
  else
    printf '{"question":"%s"}' "$QUESTION"
  fi
})

echo "→ POST ${ENDPOINT}"
echo "  问：${QUESTION}"
[ -n "$PROVIDER" ] && echo "  provider：${PROVIDER}"
echo

if [ "$STREAM" = "true" ]; then
  # SSE：-N 关掉 curl 的输出缓冲，否则要等流结束才看得到内容
  if command -v python >/dev/null 2>&1; then
    curl -sS -N -X POST "$ENDPOINT" \
      -H 'Content-Type: application/json' \
      -H 'Accept: text/event-stream' \
      -d "$payload" | python -u "$SCRIPT_DIR/print-sse.py"
  else
    curl -sS -N -X POST "$ENDPOINT" \
      -H 'Content-Type: application/json' \
      -H 'Accept: text/event-stream' \
      -d "$payload"
  fi
  echo
else
  curl -sS -X POST "$ENDPOINT" \
    -H 'Content-Type: application/json' \
    -d "$payload" | { command -v jq >/dev/null 2>&1 && jq . || cat; }
  echo
fi
