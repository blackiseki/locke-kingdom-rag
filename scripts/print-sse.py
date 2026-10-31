#!/usr/bin/env python3
"""把 /api/chat/stream 的结构化 SSE 事件打印成人类可读的调试输出。"""

import json
import sys

event = "message"
data = []


def flush():
    global event, data
    if not data:
        event = "message"
        return
    raw = "\n".join(data)
    data = []
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError:
        print(f"\n[{event}] {raw}")
        event = "message"
        return

    kind = obj.get("type") or event
    if kind == "delta":
        # 正文直接连着打，才能看出首 token 延迟和流式效果
        sys.stdout.write(obj.get("delta") or "")
        sys.stdout.flush()
    elif kind == "meta":
        sources = obj.get("sources") or []
        print(
            f"[meta] provider={obj.get('provider')} model={obj.get('model')} "
            f"retrieval={obj.get('retrievalMs')}ms sources={len(sources)}"
        )
    elif kind == "done":
        print(
            f"\n[done] provider={obj.get('provider')} model={obj.get('model')} "
            f"prompt={obj.get('promptTokens')} completion={obj.get('completionTokens')} "
            f"total={obj.get('totalTokens')} estimated={obj.get('estimated')} "
            f"fallback={obj.get('fallback')} success={obj.get('success')} "
            f"server={obj.get('totalLatencyMs')}ms retrieval={obj.get('retrievalMs')}ms "
            f"llm={obj.get('latencyMs')}ms"
        )
        for attempt in obj.get("attempts") or []:
            print(
                f"  attempt {attempt.get('provider')}/{attempt.get('model')}: "
                f"{attempt.get('status')} {attempt.get('latencyMs')}ms"
                + (f" error={attempt.get('error')}" if attempt.get("error") else "")
            )
    elif kind == "error":
        print(f"\n[error] {obj.get('message')}")
    else:
        print(f"\n[{kind}] {json.dumps(obj, ensure_ascii=False)}")
    event = "message"


for line in sys.stdin:
    line = line.rstrip("\r\n")
    if not line:
        flush()
    elif line.startswith(":"):
        continue
    elif line.startswith("event:"):
        event = line[len("event:"):].strip()
    elif line.startswith("data:"):
        data.append(line[len("data:"):].lstrip())

flush()
