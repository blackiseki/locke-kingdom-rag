# 洛克王国 RAG 助手 (locke-kingdom-rag)

> **一个能完整跑通的 RAG 练手项目。** 把《洛克王国》的游戏资料做成向量库，
> 亲手走一遍「文档切分 → 向量化 → 入库 → 检索 → 拼 Prompt → 大模型回答」的全流程。

## 这是什么

很多人学 RAG 卡在同一个地方：概念都懂了——要先切 chunk，要算 embedding，要存向量库，
检索到相关内容再塞进 Prompt——但**具体到代码里，这一步到底发生在哪一行**说不清楚。

这个项目就是为了把这条线走完整而写的：一个基于 Spring Boot + Spring AI + PgVector 的
问答服务，语料是《洛克王国》的精灵、技能、道具和属性克制表。从建库、入库到提问，
每一步都有可复现的命令；想换成你自己的语料，也不用改代码，只要换一个目录。

**它想帮新手解决三件事：**

- **看得见** — 检索不是黑盒。query 怎么变成向量、top-k 怎么筛、阈值怎么定、命中片段怎么拼进 Prompt，每一步都有明确代码位置和可调参数。
- **跑得起来** — 语料 → 入库 → 提问，三步可复现；自带语料样例格式，换成你自己的数据只要改一个目录。
- **读得懂** — [`docs/vector-database-guide.md`](docs/vector-database-guide.md) 从「为什么需要向量库」一路讲到本项目实际执行的每一条 SQL，附 6 个能照着跑的实验、10 条踩坑清单和 8 道自测题。

**建议的阅读顺序**（别一上来就翻代码）：

1. 按[「快速开始」](#快速开始)把服务跑起来，先问它三个问题——感受一下 RAG 答得准在哪、答歪在哪
2. 读 [`docs/vector-database-guide.md`](docs/vector-database-guide.md)，把向量库这块的空白补上
3. 顺着链路看三处代码：`ingestion/`（怎么切、怎么存）→ `RetrieverService`（怎么查）
   → `ChatService`（怎么拼、怎么流式吐回去）
4. 想换模型或换成自己的语料，看[「你需要自己配的东西」](#你需要自己配的东西)

除了 RAG 主干，项目里还顺手做了几件工程上的事，可以按兴趣挑着看：
**多 Provider 运行时切换与失败降级**（DeepSeek / 通义千问）、**结构化 SSE 流式**、
**token / 耗时上报**、**多轮会话的记忆压缩**。这些不是 RAG 的必需品，但接近真实项目的样貌。

> ⚠️ **这是学习项目，不是生产系统。** `/api/ingest` 没有鉴权、指标只在进程内聚合、
> 结构化表是全量重建。已知边界都写在[最后一节](#已知边界)，别直接拿去上线。

语料来自 **[rocom.aoe.top](https://rocom.aoe.top) 数据站导出的游戏数据包**（`public/data`），
包含 1065 只精灵、475 个技能、4540 件道具、19 个属性的克制表等。
语料不入库（体积与版权原因），仓库里只留样本格式，真实数据需要自己导一份放到 `LOCKE_DATA_ROOT`。

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17 | Spring Boot 4.1 要求至少 17 |
| Spring Boot | 4.1.1 | |
| Spring AI | 2.0.1 | 只保留 `EmbeddingModel` / `VectorStore`；对话由自建传输层负责 |
| 对话模型 | DeepSeek / 通义千问（OpenAI-compatible） | `com.locke.ai.llm`：JDK `HttpClient` + Jackson，运行期按请求选择 |
| 流式与降级 | SSE + Provider 路由 | `meta/delta/done/error` 事件；失败按候选顺序自动切换 |
| 可观测性 | token / 耗时 / attempts | `ChatResponse`、SSE `done`、`GET /api/llm/metrics` |
| 会话记忆 | 最近原文 + 滚动摘要 | `chat_session`/`chat_message`；超出 token 预算的旧历史压成摘要进 system |
| 向量模型 | 本地 Ollama `bge-m3`（1024 维） | DeepSeek **没有** embedding 接口，必须另配 |
| 向量库 | PostgreSQL 17 + pgvector | `spring-ai-starter-vector-store-pgvector` |
| 迁移 | Flyway | DDL 唯一来源 |

## 你需要自己配的东西

### 1. 运行环境（缺一个都跑不起来）

| 项 | 检查方式 | 说明 |
| --- | --- | --- |
| **JDK 17+** | `java -version` / `echo $env:JAVA_HOME` | `JAVA_HOME` 要指向 JDK 根目录，且 `PATH` 里能找到 `java`；两者缺一个 `./mvnw` 就会报`JAVA_HOME is not defined correctly`。推荐 JDK 21 LTS |
| **Docker Desktop** | `docker ps` | 用它一键起 pgvector + ollama；也可以不用 Docker，本地自己装 PostgreSQL 17 + pgvector + Ollama（见「快速开始 A / B」） |
| **Ollama 模型** | `docker exec -it locke-ollama ollama list` | 需要 `bge-m3`（约 1.2GB）。默认配置会自动拉（`pull-model-strategy: when_missing`），也可以手工 `ollama pull bge-m3` |

### 2. 应用配置

至少给默认 Provider 配置密钥；通义可选，没配 key 时会被标记为不可用并跳过。

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `DEEPSEEK_API_KEY` | ✅（默认 Provider 是 DeepSeek 时） | 无 | platform.deepseek.com |
| `DASHSCOPE_API_KEY` | ❌ | 无 | 通义千问 / DashScope；不配也能只用 DeepSeek |
| `LLM_PROVIDER` | ❌ | `deepseek` | 默认 Provider；请求体的 `provider` 可覆盖 |
| `LLM_FALLBACK` | ❌ | `tongyi` | 降级顺序，逗号分隔，如 `tongyi,deepseek` |
| `DEEPSEEK_BASE_URL` | ❌ | `https://api.deepseek.com` | 可指向任意 OpenAI-compatible 网关 |
| `DEEPSEEK_MODEL` | ❌ | `deepseek-flash` | 也可用 `deepseek-v4-pro` |
| `DASHSCOPE_BASE_URL` | ❌ | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 通义 OpenAI-compatible 入口 |
| `DASHSCOPE_MODEL` | ❌ | `qwen-plus` | |
| `TONGYI_ENABLED` | ❌ | `true` | 设为 `false` 可完全不加载通义 |
| `LOCKE_DATA_ROOT` | ❌ | 无（示例见 `data/`） | 语料根目录，指向你导出的数据包的 `public/data` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | ❌ | `jdbc:postgresql://localhost:5432/locke` / `postgres` / `postgres` | |
| `OLLAMA_BASE_URL` | ❌ | `http://localhost:11434` | 应用在容器里跑时要用 `http://ollama:11434` |
| `EMBEDDING_MODEL` | ❌ | `bge-m3` | 换模型必须同步改维度，见下 |
| `INGEST_IMPLEMENTED_ONLY` | ❌ | `true` | 只入已实装的 613 只精灵 |
| `MAX_MOVES_PER_DOC` | ❌ | `25` | 单篇精灵档案最多列几个升级技能 |
| `MEMORY_ENABLED` | ❌ | `true` | 会话记忆总开关；关掉后所有请求退化为单轮 |
| `MEMORY_MAX_HISTORY_TOKENS` | ❌ | `1200` | 历史原文的 token 预算 |
| `MEMORY_KEEP_RECENT` | ❌ | `6` | 至少保留原文的最近消息条数 |
| `MEMORY_SUMMARY_TOKENS` | ❌ | `400` | 滚动摘要长度上限 |

**换向量模型时必须同时改三处**，否则写库会报 `expected 1024 dimensions, not N`：

1. `spring.ai.ollama.embedding.model`
2. `spring.ai.vectorstore.pgvector.dimensions`
3. `src/main/resources/db/migration/V1__init_schema.sql` 里的 `vector(1024)`（已建表的话还要写条迁移改列类型）

**密钥怎么给**：项目已在 `application.yml` 里配置
`spring.config.import: optional:file:.env[.properties]`，所以本地 `.env` 会被加载；
Docker 跑时由 compose 从同目录 `.env` 注入。也可以直接在 IDE Run Configuration
或系统环境变量里设置。

## 快速开始

下面这条路径是在 Windows 上**实测跑通**的（不需要 Docker、不需要 WSL2、不需要虚拟化）。
如果你有可用的 Docker，也可以用 `docker compose up -d pgvector ollama` 代替 A、B 两步。

### A. 数据库：原生 PostgreSQL 17 + pgvector

```powershell
# 0) 先把 PostgreSQL 的落地目录定好，后面都用这个变量（按你自己习惯改）
$PG = 'D:\pgsql'

# 1) 下载 PostgreSQL 免安装版（325MB，解压即用，无需安装程序和管理员权限）
#    https://sbp.enterprisedb.com/getfile.jsp?fileid=1260616
#    → 解压到 $PG

# 2) 下载 pgvector 的 Windows 编译版（0.2MB）
#    ⚠ 必须匹配 PostgreSQL 大版本：pg17 的文件不能用在 pg18 上
#    https://github.com/andreiramani/pgvector_pgsql_windows/releases/download/0.8.6_17/vector.v0.8.6-pg17.zip
#    → lib\vector.dll         复制到 $PG\lib\
#      share\extension\vector* 复制到 $PG\share\extension\

# 3) 初始化数据目录（-E UTF8 是必须的，否则中文全是乱码）
'postgres' | Set-Content -NoNewline $env:TEMP\pgpw.txt
& "$PG\bin\initdb" -D "$PG\data" -U postgres -E UTF8 --locale=C `
    --auth-local=trust --auth-host=scram-sha-256 --pwfile=$env:TEMP\pgpw.txt
Remove-Item $env:TEMP\pgpw.txt

# 4) 建库 + 装扩展（建表由 Flyway 负责，这里只装扩展）
& "$PG\bin\pg_ctl" -D "$PG\data" -l "$PG\pg.log" start
Start-Sleep 5
& "$PG\bin\createdb" -h 127.0.0.1 -U postgres locke
& "$PG\bin\psql"   -h 127.0.0.1 -U postgres -d locke -c "CREATE EXTENSION vector;"

# 5) 【建议】注册成 Windows 服务，之后就不用再手工启动了
#    这么做的真正理由不是省事，而是为了摆脱下面这个坑（我踩过，数据库被打死过一次）：
#    手工用 pg_ctl 启动时，postgres 会继承启动它的那个 shell 的管道/控制台句柄。
#    于是只要那个 shell 被结束、进程树被清理、或者你关掉终端窗口，
#    数据库就可能随之被强行终止（pg.log 里看不到任何 shutdown 记录，只能靠 WAL 崩溃恢复）。
#    托管成服务后它由 SCM 拉起，与任何终端彻底解绑，并且开机自启。
icacls "$PG\data" /grant "NT AUTHORITY\SYSTEM:(OI)(CI)F" /T
& "$PG\bin\pg_ctl" register -N postgresql-17 -D "$PG\data" -S auto
Start-Service postgresql-17
```

> ⚠️ **给 psql 传含中文的 SQL 时不要用 `-c`**：Windows 命令行是 GBK，
> psql 按 UTF8 解读会报 `invalid byte sequence for encoding "UTF8"`。
> 把 SQL 写进 UTF-8 文件、用 `psql -f 文件` 才对。（这个坑我在同一个晚上踩了三次。）

### B. 向量模型：Ollama + bge-m3

```powershell
# 1) 装 Ollama（GitHub 官方安装包，含 CUDA 运行时）
#    https://github.com/ollama/ollama/releases/download/v0.40.4/OllamaSetup.exe

# 2) 拿 bge-m3 权重（ModelScope，实测 19.8MB/s；走 ollama pull 只有 0.09MB/s，要 3 小时）
#    https://modelscope.cn/api/v1/models/gpustack/bge-m3-GGUF/repo?Revision=master&FilePath=bge-m3-FP16.gguf

# 3) 导入（在该目录下建 Modelfile，内容一行：FROM ./bge-m3-FP16.gguf）
ollama create bge-m3 -f Modelfile
ollama list          # 应看到 bge-m3:latest

# 4) 验证维度（必须是 1024，与 V1__init_schema.sql 里的 vector(1024) 一致）
curl http://localhost:11434/api/embed -d '{"model":"bge-m3","input":"火系精灵"}'
```

### C. 起应用 + 入库 + 提问

```powershell
# 1) 配密钥（.env 已被 gitignore；application.yml 里只有占位符）
#    .env 内容：
#      DEEPSEEK_API_KEY=sk-xxx
#      DASHSCOPE_API_KEY=sk-xxx      # 可选，用于通义降级

# 2) 先 dryRun 看规模（不写库、不调嵌入）
curl -X POST localhost:8080/api/ingest -H 'Content-Type: application/json' `
     -d '{"source":"all","dryRun":true}'
# 实测：5755 篇文档 → 5812 个 chunk，跳过 452 只未实装精灵

# 3) 正式入库（实测 1.6 分钟，RTX 4090 上 25,140 字/秒）
curl -X POST localhost:8080/api/ingest -H 'Content-Type: application/json' `
     -d '{"source":"all","dryRun":false}'

# 4) 提问
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' `
     -d '{"question":"火系克制哪些属性？"}'
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat` | RAG 问答（JSON）。请求体 `{question, topK?, provider?, sessionId?, memory?}`；响应含 `provider/model/tokens/retrievalMs/llmLatencyMs/latencyMs/fallback/attempts/sessionId/historyTokens/contextCompressed` |
| POST | `/api/chat/stream` | 结构化 SSE 流式问答，事件为 `meta/delta/done/error`；`meta` 与 `done` 带 `sessionId` |
| GET | `/api/sessions` | 最近活跃的会话列表（`?limit=20`），按 updatedAt 倒序 |
| POST | `/api/sessions` | 新建会话，请求体 `{id?, title?}` 可省略；id 已存在时返回原会话、不覆盖历史 |
| GET | `/api/sessions/{id}` | 会话详情：滚动摘要 + 最近消息（`?limit=100`），每条消息带 `summarized` 标记 |
| DELETE | `/api/sessions/{id}` | 删除会话（消息级联删除），不存在返回 404 |
| POST | `/api/sessions/{id}/compact` | 手动压缩：把较早历史折叠进摘要，返回折叠条数与摘要 token |
| GET | `/api/llm/providers` | Provider 目录：默认、fallback 顺序、每个 Provider 的 model/status/available |
| GET | `/api/llm/metrics` | 进程内聚合指标：请求数、成功/失败、token、平均/最大耗时；重启归零 |
| POST | `/api/ingest` | 触发入库，支持 `dryRun`；`source` 可取 `all/pets/moves/items/reference/wiki` |
| GET | `/api/spirits/{name}` | 按中文名精确查精灵（含技能） |
| GET | `/api/spirits/search?q=` | 按中文名模糊搜索 |
| GET | `/api/spirits/by-element/{element}` | 按属性列表，如 `/by-element/火` |

### 多轮会话与上下文压缩

不带 `sessionId` 时，每轮都是独立的单轮问答，但响应里会回一个新的 `sessionId`；
把它带到下一轮，模型就能接着上文回答（「那它的进化型呢？」这种省略主语的追问）。

```bash
SID=$(curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
      -d '{"question":"火系克制哪些属性？"}' | python -c 'import json,sys;print(json.load(sys.stdin)["sessionId"])')

curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d "{\"question\":\"那它被哪些属性克制？\",\"sessionId\":\"$SID\"}"
```

**上下文压缩**（滚动摘要，不是简单截断）：

```text
        ┌───────────── 进 prompt 的内容 ─────────────┐
摘要    │ 【本会话此前的对话摘要】……（压缩产物）        │  ← summary，覆盖 id <= summary_upto_id
原文    │ user/assistant × N（按 token 预算从新到旧保留）│  ← 水位线之后的消息
本轮    │ 【参考资料】+【用户问题】                     │
        └────────────────────────────────────────────┘
```

- 每轮请求前先按 `max-history-tokens` 从**最新往最老**挑历史，挑不下的（溢出的较早部分）就是待折叠候选。
- 候选数 ≥ `compress-min-messages` 才真的压缩：让模型把「上一版摘要 + 这批消息」重写成新版摘要，
  水位线（`summary_upto_id`）推进到被折叠的最后一条。摘要和本轮问答**一起原子更新**，
  不会出现「摘要换了、水位没动」导致同一段历史被折叠两次。
- **只溢出 1 条不压**：把刚说完的话立刻换成摘要既丢细节又白花一次模型调用。
- **永远保留最近 `keep-recent-messages` 条原文**，追问才有指代对象。
- 压缩模型不可用时退化成**抽取式摘要**（旧摘要 + 每条消息首句），绝不把异常抛进问答主链路；
  存储故障同理——记忆是增强能力，失败就退化成单轮，用户照样能提问。
- 兜底文案、流式中途失败的半截答案**不写历史**，否则下一轮模型会看到一堆「抱歉，服务暂时不可用」。

**顺带解决的一个坑**：光是「模型看得懂上文」不够，检索也得找得到。上一轮问「火系克制哪些属性」，
这一轮问「那它被哪些属性克制？」——原句直接拿去做向量检索召回的全是无关内容（实测模型会答「资料里没有」）。
所以当问题里出现指代词（它/这个/刚才…）或问题短于 8 字时，会用上一轮问题拼成检索语句
（`locke.ai.memory.rewrite-retrieval-query`，默认开）。这是启发式规则，日志里会打 `检索语句按历史补全`。

配置（`locke.ai.memory`，完整项见 `application.yml`）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 全局开关；关掉后所有请求退化成单轮 |
| `max-history-tokens` | `1200` | 历史原文的 token 预算（不含摘要、不含本轮资料） |
| `keep-recent-messages` | `6` | 至少保留原文的最近消息条数 |
| `max-history-messages` | `60` | 单次加载的最大历史条数 |
| `compress-min-messages` | `2` | 溢出多少条才触发压缩 |
| `summary-max-tokens` | `400` | 摘要长度上限（中文近似 1 字 1 token） |
| `rewrite-retrieval-query` | `true` | 指代不明的追问是否用上一轮问题补全检索语句 |

请求体里传 `"memory": false` 可强制单轮（做评测时排除历史干扰）。
会话存在 `chat_session` / `chat_message` 两张表（`V2__conversation_memory.sql`），
一个会话只有一行摘要，消息原始记录全部保留，`GET /api/sessions/{id}` 能看到哪些已被折叠。


### 多 Provider 切换与失败降级

请求体里的 `provider` 可选；不传时用 `locke.ai.llm.provider`。
实际尝试顺序是：

```
请求指定的 provider  →  默认 provider  →  locke.ai.llm.fallback  →  其余已配置 provider
```

同一个 provider 只尝试一次。配置了但缺 key / base-url / model 的 Provider 会显示为
`missing api-key` 等状态，在路由里记为 `skipped`，不会发起请求。HTTP 408/409/429/5xx
和网络超时被视为可重试；401/404 等配置类错误仍会记录，但不会无限重试。

- **同步接口**：所有候选都失败时返回 200 + 兜底文案，`fallback=true`，`attempts`
  里保留每次失败原因和耗时。
- **流式接口**：还没输出任何正文时，可以整段换 Provider；如果已经输出了一部分再失败，
  不会把两个 Provider 的答案拼在一起，而是发 `error` + `done(success=false)` 收尾。
- 未知 `provider` 会在进入 SSE 前直接返回 400。

配置示例（完整项见 `src/main/resources/application.yml`）：

```yaml
locke:
  ai:
    llm:
      provider: ${LLM_PROVIDER:deepseek}
      fallback: ${LLM_FALLBACK:tongyi}
      providers:
        deepseek:
          enabled: true
          base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
          api-key: ${DEEPSEEK_API_KEY:}
          model: ${DEEPSEEK_MODEL:deepseek-flash}
          extra-body:
            thinking:
              type: disabled
        tongyi:
          enabled: ${TONGYI_ENABLED:true}
          base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
          api-key: ${DASHSCOPE_API_KEY:}
          model: ${DASHSCOPE_MODEL:qwen-plus}
```

DeepSeek 的 `thinking.type: disabled` 不是装饰：实测同一个问题从 3028ms 降到 717ms，
并且思考模式下 `temperature` 会被静默忽略。

### SSE 事件格式

`POST /api/chat/stream` 返回 `text/event-stream`，每个事件的 `event:` 与 JSON 里的
`type` 一致。典型顺序：

```text
event: meta
data: {"type":"meta","provider":"deepseek","model":"deepseek-flash","retrievalMs":42,"sources":[...]}

event: delta
data: {"type":"delta","provider":"deepseek","model":"deepseek-flash","delta":"火系克制"}

event: done
data: {"type":"done","provider":"deepseek","model":"deepseek-flash","answer":"...","promptTokens":812,"completionTokens":96,"totalTokens":908,"estimated":false,"latencyMs":1100,"retrievalMs":42,"totalLatencyMs":1210,"fallback":false,"success":true,"attempts":[...]}
```

- `meta` 带检索耗时和 `sources`；`done` **不重复**带 `sources`，前端应保留 `meta` 里的来源。
- `delta` 只增量输出正文；`answer` 在 `done` 里给最终全文，方便前端校正。
- `done.success=false` 表示发生了兜底或流式中途失败；`done.fallback=true` 表示实际
  Provider 不是请求/默认 Provider。
- 服务端 usage 可用时 `estimated=false`；缺失时用 `TokenCounter` 估算并置 `estimated=true`。
- 流式请求会带 `stream_options.include_usage=true`，尽量让 Provider 在末尾返回 usage。

## 语料构成

`RealDataParseTest` 会直接跑真实数据并把规模打进日志，下面是**实测值**（不是估算）：

| 档 | 来源 | 文档数 | 字符数 | 说明 |
| --- | --- | --- | --- | --- |
| **核心** | `pets/{id}.json` → 精灵档案 | **613** | **87.7 万** | 每只渲染成一篇 Markdown（中文名/属性/种族值/技能表/进化链/图鉴介绍/栖息地/刷新地点）。单篇最长 1904 字，都在切分上限内。**这是回答质量的主要来源** |
| **核心** | `moves.json` | **475** | — | 技能总表，单独成篇 |
| **核心** | `types.json` | 19 | — | 属性克制，同时写进 `element_type` 表 |
| **核心** | `game_terms.json` | 53 | — | 术语（印记等），中文解释完整 |
| **核心** | `personalities.json` | 30 | — | 性格及其能力修正 |
| **核心** | `magic_items.json` | 5 | — | 血脉魔法 |
| **补充** | `items.json` | **4540** | 约 15 万 | 道具，描述合计 15 万字，很便宜 |
| **补充** | `BinData/*.md` | 18 个文件 | 约 19 万 | **数据字典**：告诉你哪张配置表装了什么，答不了游戏问题 |
| **不入** | `BinData/*.json` | 745 个 / **291MB** | — | 原始数值配置表，信噪比太低 |

合计约 **5800 篇文档、110 万字**量级（bge-m3 是本地跑，没有 API 费用，
主要成本是 613+4540 次嵌入计算，在 CPU 上大概要几分钟到十几分钟）。

几个关于数据的事实（都是实测出来的，不是推测）：

- `Pets.json` 和 `pets/{id}.json` 的 id **1:1 完全重合**（1065 vs 1065，交集 1065），
  而后者是自包含的完整记录，所以只读 `pets/`，不读 `Pets.json`。
- 1065 只里只有 **613 只 `implemented=true`**。其余 452 只是测试/未上线数据，
  文案是策划占位内容（例如 id 10000 的介绍写着「这是一只测试宠物，
  如果你在不该看到的地方看到了它，请联系策划」），默认被过滤掉。
- **28 只（4000+ 的首领/终极形态）六项种族值全为 0**——PETBASE 里本来就没有，
  不是解析失败。实测 585/613 能读出种族值。
- **333/613 能解析出进化等级**。数据里没有结构化的等级字段，
  只有进化条件里那句中文（`"等级达到 36 级"`），代码从那里正则提取；
  剩下的靠道具/羁绊进化，没有等级。
- **`localized.zh` 有三副面孔**：精灵和技能上是对象 `{name, description}`，
  属性/血脉上是字符串 `"地"`，道具则完全没有 `localized`、中文直接写在 `description` 里。
  代码里统一由 `LocalizedText` 处理。
- **技能学习等级拿不到**：它在原始配置表（`LEVEL_SKILL_CONF`）里有，
  但生成 `pets/{id}.json` 的脚本把它丢掉了。所以精灵档案里**不写**技能等级，
  而不是编一个。
- `moves.json` 的 id（1~3643）和精灵内嵌技能 id（7 位数）**交集为 0**，是两套数据，
  所以技能总表值得单独入向量库。
- `catch_info` 那三个数字的确切含义数据源没说明，**只入库、不进向量档案**——
  宁可答不出来，也不要给一个似是而非的说明。

## 目录结构

```
locke-kingdom-rag/
├── pom.xml
├── Dockerfile                            # 为 docker-compose 的 app 服务准备
├── docker-compose.yml                    # pgvector + ollama + app
├── data/                                 # 预留的本地语料目录（真实语料在 LOCKE_DATA_ROOT）
├── scripts/{ingest.sh,test-chat.sh,print-sse.py}
└── src/
    ├── main/java/com/locke/ai/
    │   ├── LockeAiApplication.java
    │   ├── config/{VectorStoreConfig,WebMvcConfig}.java
    │   ├── controller/{ChatController,IngestController,LlmController,SpiritController,SessionController}.java
    │   ├── service/
    │   │   ├── {ChatService,IngestService,RetrieverService,PromptBuilder}.java
    │   │   ├── ConversationMemory.java                # 多轮记忆：预算裁剪 + 触发压缩
    │   │   ├── ConversationCompressor.java            # 滚动摘要（带抽取式兜底）
    │   │   └── ConversationService.java               # 会话 CRUD + 手动压缩
    │   ├── domain/
    │   │   ├── entity/{Spirit,Skill,TypeRef,ConversationSession,ConversationMessage}.java
    │   │   ├── dto/{ChatRequest,ChatResponse,ChatStreamEvent,IngestResult,TypeRecord,
    │   │   │          SessionView,SessionDetailView,CreateSessionRequest,CompactResult}.java
    │   │   └── enums/{ElementType,ChatRole}.java
    │   ├── repository/{SpiritRepository,VectorRepository,ConversationStore,JdbcConversationStore}.java
    │   ├── ingestion/
    │   │   ├── reader/{DataRoot,JsonDataReader,MoveDataReader,ItemDataReader,
    │   │   │           ReferenceDataReader,WikiMarkdownReader}.java
    │   │   ├── splitter/SpiritChunkSplitter.java      # 渲染「精灵档案」
    │   │   └── transformer/DocumentEnricher.java      # 元数据 + 确定性 id
    │   ├── llm/                                      # 自建 OpenAI-compatible 传输层
    │   │   ├── LlmProperties.java                    # locke.ai.llm.* 配置
    │   │   ├── LlmProvider.java / OpenAiCompatibleProvider.java
    │   │   ├── LlmProviderRegistry.java              # 启动时装配、可查询目录
    │   │   ├── LlmRouter.java                        # 选择、降级、attempts
    │   │   ├── LlmMetrics.java                       # 进程内指标聚合
    │   │   └── LlmRequest/Message/Completion/Chunk/Usage/Attempt/CallException.java
    │   ├── exception/…
    │   └── util/{JsonUtils,LocalizedText,TokenCounter}.java
    └── test/java/com/locke/ai/
        ├── service/ChatServiceTest.java               # RAG 编排 + 结构化 SSE
        ├── service/ConversationMemoryTest.java        # 预算裁剪 / 压缩 / 水位线（内存 store）
        ├── service/ConversationCompressorTest.java    # 滚动摘要 + 兜底路径
        ├── support/InMemoryConversationStore.java     # 单测用的会话存储替身
        ├── llm/OpenAiCompatibleProviderTest.java      # 本地 HttpServer stub
        ├── llm/LlmRouterTest.java                     # 降级顺序与流式边界
        ├── config/ApplicationConfigTest.java          # 配置契约
        ├── ingestion/IngestServiceTest.java           # 用 @TempDir 造同形状样本
        ├── ingestion/RealDataParseTest.java           # 直接跑真实数据（无数据时自动跳过）
        └── integration/ChatApiIntegrationTest.java    # -Dlocke.it=true 才跑
```

## 测试

```bash
./mvnw test                      # 单测 + 真实数据解析测试（不需要数据库/Ollama）
./mvnw test -Dlocke.it=true      # 额外跑端到端接口测试（需要 pgvector）
./mvnw test -Dlocke.data.root=D:/other/data   # 指向别的数据目录
```

默认执行 **45 个测试：40 通过、5 跳过**（`ChatApiIntegrationTest` 需要
`-Dlocke.it=true` 和 PostgreSQL 才会跑）。其中：

- `ChatServiceTest` 覆盖 RAG 编排、未知 Provider 400、结构化 SSE 降级；
- `OpenAiCompatibleProviderTest` 用本地 `HttpServer` 验证普通响应、SSE、429 和脱敏；
- `LlmRouterTest` 覆盖同步/流式降级与 attempts；
- `ApplicationConfigTest` 守住 `spring.ai.model.chat=none`、Provider 配置和密钥占位符。

`RealDataParseTest` 会直接读真实数据做断言，并把**实际语料规模打进日志**——
想知道这次入库会产生多少向量，看它的日志或者调 `/api/ingest` 的 `dryRun` 即可。

流式接口可以用脚本观察结构化事件：

```bash
STREAM=true PROVIDER=deepseek ./scripts/test-chat.sh
# 没有 python 时会回退为原始 SSE；有 python 时由 scripts/print-sse.py 格式化
```

## 环境排错

这几个是实际踩到过的问题，症状和原因都记下来免得再查一遍。

### 1. Docker Desktop 起来了，但 `docker ps` 报 500，`wsl -l -v` 没有任何输出

**根因**：Docker Desktop 的 Linux 引擎依赖 WSL2，而 WSL2 需要
`VirtualMachinePlatform` 这个 Windows 特性。常见情况是
`Microsoft-Windows-Subsystem-Linux` 已启用、但 `VirtualMachinePlatform` 未启用，
于是 GUI 能启动、引擎一直起不来（backend 日志里会连续刷
`backend is not running`）。

先查状态（不需要管理员）：

```powershell
(Get-CimInstance Win32_OptionalFeature -Filter "Name='VirtualMachinePlatform'").InstallState
# 1=已启用  2=已禁用  3=不存在
```

如果是 `2`，**以管理员身份**执行，然后**重启电脑**（这步跳不过）：

```powershell
wsl --install --no-distribution
```

`--no-distribution` 表示只装 WSL2 平台和内核，不额外装 Ubuntu
（Docker 会自己建 `docker-desktop` 发行版）。如果这条命令不可用，退回传统写法：

```powershell
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
# —— 重启 ——
wsl --set-default-version 2
wsl --update
```

重启后验证，然后**从托盘完全退出 Docker Desktop 再重新启动**
（它要重新初始化 WSL 集成）：

```powershell
wsl --status      # 「默认版本」应为 2
wsl --version     # 之前是空输出，现在应有内容
docker version    # Server 段应能正常显示
```

**不需要开 Hyper-V**——那是 Docker 的另一条后端路线，性能不如 WSL2，不必两条都开。

### 2. `./mvnw` 报 `JAVA_HOME is not defined correctly`

常见原因是 `JAVA_HOME` 未设置、或者 `PATH` 里没有 `java`。
装一个 JDK 21 LTS 并设好 `JAVA_HOME` 即可。只做临时验证的话，直接在会话里指一下：

```powershell
$env:JAVA_HOME='<你的 JDK 目录，例如 C:\Program Files\Java\jdk-21>'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
./mvnw spring-boot:run
```

### 3. 控制台里中文报错是乱码

Windows 控制台默认代码页是 GBK，而 javac / JUnit 输出 UTF-8 中文，
两者对不上就会出现「乱码 + 换行被吃掉」。不是文件坏了。让报错改用英文即可：

```powershell
$env:MAVEN_OPTS='-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8'
```

### 4. Ollama 同时装了原生版和容器版

原生 Windows 版和 `docker compose` 里的 `ollama` 服务**都监听 11434**，会抢端口。
二选一：

- 用原生版：不要把 `ollama` 加进 `docker compose up`，容器里的 app 用
  `SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434`
- 用容器版：先退出原生 Ollama（托盘图标 → Quit），再 `docker compose up -d ollama`

## 已知边界

### 可观测性只做到进程内聚合

`GET /api/llm/metrics` 的请求数、成功率、token、平均/最大耗时保存在
`ConcurrentHashMap` 里，**重启归零**，没有 Prometheus 导出、没有 trace、没有 P95/P99，
也没有按时间窗口的滑窗统计。它适合单机排障和演示，不等于生产级 APM。

### Docker / WSL2 在部分 Windows 机器上修不动

极端情况下问题不在开关，而在系统本身：`C:\Windows\WinSxS\pending.xml` 里堆着一批
没落地的服务事务（文件异常大，正常只有几百 KB），导致 `VirtualMachinePlatform`
永久停在 `EnablePending`、`vmcompute.exe` 从未落盘。
症状是 `wsl --status` 报「未启用虚拟化」、`docker ps` 报 500。

如果「启用特性 → 重启」「disable/enable 重装 FoD」「`DISM /RestoreHealth`」
都试过且指标纹丝不动，就别再在这上面耗时间了。

**本项目就是被这个逼出来的**：改用原生方案完全绕开它（原生 PostgreSQL + 原生 Ollama），
不需要虚拟化也能跑通全流程——这也是「快速开始」里那套原生安装步骤存在的原因。
若确实需要 Docker，剩下的选项只有就地修复升级（挂 ISO 跑 setup，保留文件与程序），
或手工删除 `pending.xml`（不建议，可能让系统再也无法安装更新）。

### 本项目踩过的几个 PowerShell / psql 坑

| 坑 | 症状 | 正确做法 |
| --- | --- | --- |
| psql 的 `-c` 传中文 SQL | `invalid byte sequence for encoding "UTF8": 0xb2` | 把 SQL 写进 UTF-8 文件，用 `psql -f` |
| `pg_ctl start` 接进 PowerShell 管道 | 命令永久挂起（`Select-Object -Last` 等不到管道关闭） | 输出重定向到**文件**，或干脆不接管道 |
| `Start-Process -Wait` 启动 `pg_ctl` | 永久挂起（`-Wait` 会等整个进程树，而 postgres 永不退出） | 不要 `-Wait`，改用轮询端口 |
| PowerShell 变量名不区分大小写 | 写了 `$SVC` 和 `$svc` 两个名字，后者把前者覆盖成对象，`Start-Service -Name $SVC` 报「找不到名为 System.ServiceProcess.ServiceController 的服务」 | 变量名别只靠大小写区分 |

共同点：**这些错误全都不报错、或者报出一个和真实原因无关的错误**，
所以每次都要回头验证真实状态，不能相信「成功」的输出。

- **没有实现配种/孵蛋**：`breeding_profile`（蛋组、性别比例）在数据里有，
  但这个业务线没做，`spirit` 表也没落这些列。
- **结构化表是全量重建**：`pets` 分支会先清空 `spirit / skill / spirit_skill` 再写，
  所以不要用它做增量更新。
- **改短文件不会清理旧向量**：文档 id 是 `hash(source + 块序号)`，
  文件变短时多出来的旧块会留在库里。补法见 `VectorRepository` 的注释。
- **`/api/ingest` 没有鉴权**：它会触发全量解析和模型调用，生产必须加
  Spring Security + 内网限制。
