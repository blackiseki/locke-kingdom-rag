#!/usr/bin/env bash
# 手动触发入库
#
# 语料来自 rocom.aoe.top 导出的游戏数据（路径由 LOCKE_DATA_ROOT 指定）：
#   pets      pets/*.json（613 只已实装精灵）—— 同时写结构化表和向量库
#   moves     moves.json（475 条技能总表）—— 只写向量库
#   items     items.json（4540 件道具）—— 只写向量库
#   reference types/game_terms/personalities/magic_items —— 同时重建 element_type 表
#   wiki      BinData/*.md（18 个数据字典文件）—— 只写向量库
#
# 用法：
#   ./scripts/ingest.sh                  # 全量入库（建议先 --dry-run 看一眼规模）
#   ./scripts/ingest.sh --dry-run        # 只解析和切分，不写库、不调嵌入模型
#   ./scripts/ingest.sh --pets --items   # 只跑其中几类
#   BASE_URL=http://1.2.3.4:8080 ./scripts/ingest.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DRY_RUN="false"
SOURCES=""

for arg in "$@"; do
  case "$arg" in
    --dry-run)   DRY_RUN="true" ;;
    --pets)      SOURCES="${SOURCES:+$SOURCES,}pets" ;;
    --moves)     SOURCES="${SOURCES:+$SOURCES,}moves" ;;
    --items)     SOURCES="${SOURCES:+$SOURCES,}items" ;;
    --reference) SOURCES="${SOURCES:+$SOURCES,}reference" ;;
    --wiki)      SOURCES="${SOURCES:+$SOURCES,}wiki" ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

if [ -z "$SOURCES" ]; then
  SOURCE="all"
elif [ "${SOURCES}" = "all" ]; then
  SOURCE="all"
else
  # 服务端一次只接受一个 source，多个就逐个跑
  SOURCE=""
fi

run_one() {
  local src="$1"
  local payload
  payload=$(printf '{"source":"%s","dryRun":%s}' "$src" "$DRY_RUN")
  echo "→ POST ${BASE_URL}/api/ingest  payload=${payload}"
  local response
  response=$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/api/ingest" \
    -H 'Content-Type: application/json' \
    -d "$payload")
  local body code
  body=$(printf '%s' "$response" | sed '$d')
  code=$(printf '%s' "$response" | tail -n1)
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$body" | jq .
  else
    printf '%s\n' "$body"
  fi
  if [ "$code" -ge 400 ]; then
    echo "✗ ${src} 入库失败，HTTP ${code}" >&2
    return 1
  fi
  echo "✓ ${src} 完成，HTTP ${code}"
}

if [ -n "$SOURCE" ]; then
  run_one "$SOURCE"
else
  IFS=',' read -ra LIST <<< "$SOURCES"
  for src in "${LIST[@]}"; do
    run_one "$src"
  done
fi
