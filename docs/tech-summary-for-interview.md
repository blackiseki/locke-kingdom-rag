# 洛克王国 RAG 助手 · 技术摘要（面试准备版）

> **本文用法**：既是项目的**事实清单**，也是按你给的面试题做的**对照表**。
> - 【已做】= 代码里真的有，能指出文件位置，多数有实测数据
> - 【未做】= 明确没有。**面试时不要往这上面编**，可以答"这个我没做，我的理解是…"
> - 所有数字都是本地实测的，不是估算；估算的会标注"≈"
>
> 项目路径：仓库根目录（`locke-kingdom-rag`）

---

## 0. 一分钟电梯陈述

一个**面向单机、中文、游戏领域**的 RAG 问答系统：把《洛克王国》游戏数据（1065 个精灵 JSON + 技能 + 道具 + 术语 + 数据字典）离线索引成 5812 条向量，用户提问时走向量检索 + 结构化兜底，把命中的原文拼进 prompt 交给大模型作答，并返回**可追溯的出处与相似度**。

生成侧不是绑死一家：`com.locke.ai.llm` 是自建的 OpenAI-compatible 传输层，支持 DeepSeek / 通义千问等 Provider 在**运行期按请求切换**，失败时按默认 → fallback → 其余可用 Provider 的顺序降级；流式接口输出 `meta/delta/done/error` 结构化 SSE，并把 token、检索/模型/总耗时和每次尝试记录一并上报。

技术栈刻意选成"每一层都能看到内部"的组合：pgvector 让我能直接写 SQL 观察向量算子，本地 Ollama + bge-m3 让嵌入过程完全可控，自建传输层让 Provider 请求/降级/用量都可验证，服务端提示词把"只依据资料作答"写成硬约束。

**最有价值的不是它跑通了，而是它跑通的过程中暴露的 4 类真实缺陷**（见 §4）——每一个都是"不报错、或者报出与真实原因无关的错误"，只能靠分层验证抓出来。

---

## 1. 系统全景

### 1.1 组件与版本

| 层 | 选型 | 版本 | 为什么 |
| --- | --- | --- | --- |
| 应用框架 | Spring Boot | 4.1.1 | Java 17 target，Spring Framework 7 |
| RAG 编排 | Spring AI | 2.0.1 | 只保留 `VectorStore` / `EmbeddingModel`；对话不再用 `ChatClient` |
| 向量库 | PostgreSQL + pgvector | 17.11 + 0.8.6 | 向量与关系数据同一个库，省一次一致性协调；SQL 可直接观察 |
| 向量索引 | HNSW + `vector_cosine_ops` | — | pgvector 支持的近似最近邻索引 |
| 嵌入模型 | bge-m3（FP16 GGUF） | 1024 维 | 中英多语言、中文表现好、8K 上下文 |
| 嵌入运行时 | Ollama（原生） | 0.40.4 | 100% GPU（RTX 4090），模型可离线导入 |
| 生成模型 | DeepSeek `deepseek-flash` + 通义 `qwen-plus` | — | 自建 OpenAI-compatible 传输层，按请求切换；DeepSeek 思考模式**关闭**（见 §2） |
| 流式/可观测 | SSE + 进程内指标 | — | `meta/delta/done/error`；token、分段耗时、attempts |
| Schema 演进 | Flyway | 12.4.0 | DDL 唯一来源，`initialize-schema: false` |
| 部署形态 | 全部原生进程 | — | 无 Docker、无虚拟化（原因见 §7.3） |

### 1.2 离线索引链路（ingest）

```
data/raw（游戏数据目录）
  ├─ pets/*.json          1065 个精灵             → 613 个 implemented=true 入库
  ├─ moves.json           技能                   → 475 条
  ├─ items.json           道具                   → 4540 条
  ├─ types/game_terms/personalities/magic_items → 107 条参考数据
  └─ BinData/*.md         数据字典（18 个 md）    → 77 个 chunk
        ↓
各 Reader（JsonDataReader / MoveDataReader / ItemDataReader / ReferenceDataReader / WikiMarkdownReader）
        ↓
SpiritChunkSplitter：把结构化对象**渲染成 Markdown 档案文本**（不是按长度硬切）
        ↓
DocumentEnricher：补 metadata + 计算**确定性 id**（必须最后跑，理由见 §3.2）
        ↓
VectorStore.add(documents)  →  批量调 Ollama 得 N×1024 向量  →  写 vector_store
        ↓  同时
SpiritRepository.replaceAll()  →  写 spirit / skill / spirit_skill / element_type 关系表
```

**实测规模**：扫描 1088 个文件 → 5755 篇文档 → **5812 个 chunk**（dryRun 预测值 = 实际写入值，完全一致）。跳过 452 只未实装精灵，0 警告。

**实测耗时**：全量入库 **1.6 分钟**（RTX 4090 上嵌入吞吐 25,140 字/秒）。

### 1.3 在线检索链路（ask）

```
POST /api/chat {question, topK?, provider?}
        ↓
ChatService.ask
  ├─ validatedProvider(request.provider)              ← 未知 Provider 直接 400
  ├─ topK = request.topKOrDefault(配置默认值)          ← 默认 5，dev profile 是 3
  ├─ RetrieverService.retrieve(question, topK, threshold)
  │     ├─ ① 向量检索（一次 Ollama 嵌入 + 一次 pgvector 查询）
  │     └─ ② 若 ① 返回空 → 结构化兜底：先按精灵名模糊查，再按属性查
  ├─ ChatService.renderContext(docs)                  ← 编号 [1][2]… + 来源 + 原文
  ├─ PromptBuilder.systemPrompt() / buildUserPrompt(context, question)
  ├─ TokenCounter.estimateAll(...)                    ← 仅作 prompt 估算/usage 缺失兜底
  ├─ LlmRouter.complete(request, provider)
  │     ├─ 候选顺序：指定 → 默认 → fallback → 其余可用
  │     ├─ 每个候选记录 LlmAttempt（success/failed/skipped + 耗时 + tokens）
  │     └─ 优先使用 Provider 返回的 usage；全部失败抛 LlmCallException(attempts)
  └─ ChatResponse{answer, sources[], provider, model,
                  promptTokens, completionTokens, totalTokens, estimatedTokens,
                  latencyMs, retrievalMs, llmLatencyMs, fallback, attempts[]}
```

流式接口走同一套检索/路由，但返回 `Flux<ServerSentEvent<ChatStreamEvent>>`：
`meta`（来源 + 检索耗时）→ `delta`（增量正文）→ `done`（最终全文 + usage + 分段耗时 +
attempts）；全部失败且尚未输出正文时发兜底 `delta` + `done(success=false)`，已经输出过
正文再失败则发 `error` + `done(success=false)`，避免拼接两个 Provider 的半截答案。

**两个容易被追问的实现细节**（代码里有明确注释，说明是刻意设计）：
1. **`renderContext` 的编号顺序与 `toSources` 的顺序严格一致**——否则模型标了 `[2]`，前端却把出处显示成另一条，"等于给出错误引用"。
2. **上下文为空时不硬答**：不编造，而是塞一句占位（`（本轮没有检索到任何参考资料）`）交给模型，由系统提示词要求它如实说没有。比后端自己编一句更可控。

---

## 2. 硬指标（可直接引用的实测数字）

| 指标 | 实测值 |
| --- | --- |
| 语料规模 | 1088 文件 / 5755 文档 / **5812 chunk** |
| 向量库行数 | 5812（`item:4540  spirit:613  skill:475  data-dictionary:77  term:53  personality:30  element-type:19  magic-item:5`） |
| 关系表 | `spirit 613` / `skill 976` / `spirit_skill 29436` / `element_type 19` |
| 向量维度 | 1024（与 SQL 里 `vector(1024)`、`EMBEDDING_DIMENSIONS` 三处一致，有测试守着） |
| 全量入库耗时 | **1.6 分钟**（嵌入吞吐 25,140 字/秒，GPU） |
| 单次问答延迟 | **1.3 ~ 3.9 秒**（含检索 + DeepSeek 调用） |
| 嵌入首调延迟 | 10.2 秒（模型载入显存），之后 0.25~1.4 秒 |
| 关闭思考模式收益 | **3028ms → 717ms（4.2×）**；输出 token 里推理占比 336/355 → 0 |
| 相似度分布 | 相关命中 0.55~0.73；**"不相关"基线约 0.33**（短文本，见 §3.3 的坑） |
| 测试 | **45 个：40 通过 / 0 失败 / 5 跳过**（跳过的 5 个是需 `-Dlocke.it=true` + PostgreSQL 的端到端） |
| Provider 降级 | 同步/流式均有单测；同步全部失败返回兜底 + attempts，流式未输出前可换 Provider |

**逐字抓到的检索 SQL**（从 DEBUG 日志里原样复制，不是示意）：

```sql
SELECT *, embedding <=> ? AS distance
FROM public.vector_store
WHERE embedding <=> ? < ?
ORDER BY distance LIMIT ?
```

> 面试可讲的点：**查询向量被绑定了两次**——SELECT 里算 distance、WHERE 里做阈值过滤；第三个参数是距离阈值（由相似度阈值换算），第四个是 topK。这说明"相似度阈值"在 pgvector 里其实是**距离上界**。

---

## 3. 对照【一、RAG 架构及各类优化方案】

### 3.1 基础架构理解

**【已做】** 完整链路见 §1.2 / §1.3。离线 = 解析 → 渲染档案 → 补 metadata → 嵌入 → 写向量库 + 关系表；在线 = 嵌入 query → 向量检索（+ 兜底）→ 拼上下文 → 渲染 prompt → 调模型 → 组装响应。

**RAG vs 微调 vs 长上下文 vs 搜索增强——本项目的实际取舍：**

| 方案 | 解决什么 | 本项目的判断 |
| --- | --- | --- |
| **RAG（选了）** | 知识**频繁变动**、需要**出处可追溯**、数据量大到塞不进上下文 | 游戏数据会随版本更新；玩家需要知道"这条结论出自哪只精灵的档案"；5755 篇文档远超上下文 |
| 微调 | 学**风格/格式/领域语气**，不擅长注入事实、更新成本高 | 没做。事实类问答微调容易过拟合且无法给出来源 |
| 长上下文 | 文档总量**能塞进**一次请求时最省事 | 本项目 810 万字，不可行；且长上下文仍有"中间遗忘"和成本问题 |
| 搜索增强 | 关键词/全文检索，精确匹配强、语义弱 | **部分采纳**：作为向量检索的兜底（§3.3） |

**边界认识**：RAG 的失效点集中在"**检索没召回正确的片段**"，而不是"模型不会答"。本项目的证据：模型在没有资料时能如实说"资料里没有找到"（系统提示词约束生效），但早期因为检索/数据问题答不出"火系克制什么"——错的不是生成，是**索引里没有那个关系**。

### 3.2 Chunking 与文档解析

**【已做】——本项目最值得讲的一题，因为它反常规。**

**不是固定长度切分，而是"按实体渲染结构化档案 + 阈值保护"：**

- 每只精灵 → 渲染成一篇约 1400 字的 Markdown 档案（标题、属性、图鉴编号、分类、特性、六项种族值、进化链、升级技能、技能石、血脉技能、图鉴介绍、刷新地点）
- **实测最长档案 1904 字**，远低于 `MAX_CHARS=3000` 的上限 → **613 只精灵 = 613 个 chunk，一条都没被切开**（这个 1:1 关系已验证）
- 只有超出上限的长文档才交给 `TokenTextSplitter` 做 token 级切分；数据字典 md 就是这样：**20 个文档 → 77 个 chunk**
- 技能列表做了分级：升级技能给全量，技能石/血脉技能只给**名字列表**（`MAX_NAME_ONLY`），因为数值型字段对语义检索贡献低、占 token 高

**size/overlap 的依据（诚实版）**：不是调参调出来的，而是**由数据形态倒推**：
1. 先测——最长档案 1904 字、平均约 1430 字（613 篇共 877,300 字）
2. 再定上限——`MAX_CHARS=3000` 留了一倍余量，保证正常档案不切
3. 上下文窗口配 8192（`num-ctx`）——**Ollama 默认 2048 会把长档案静默截断**，这是必须显式改大的坑

**为什么"按实体"而不是"按长度"**：一只精灵的属性、特性、种族值、进化链、技能是**语义自洽的整体**。按长度切会把"进化条件"和"进化目标"切到两个 chunk 里，检索到任一半都答不对。同理，一个道具/一条术语各自成文档——4540 个道具就是 4540 个 chunk。

**【未做】** 表格（本项目数据是 JSON，没有 PDF/扫描件）；代码、FAQ 的专门处理；overlap 调优（因为基本不需要切分，overlap 没意义）。

**面试可延伸**：真实项目里文档形态是混合的，我的策略是**按结构能保就保、不能保才退到长度切分**，并且用 overlap 兜住语义断裂点。表格要么转成"表头: 值"的键值文本（本项目 `catch_info` 就是这么处理的），要么整表一个 chunk 避免割裂。

### 3.3 Embedding 与向量检索

**【已做】**

**模型选型**：bge-m3（1024 维、多语言、8K 上下文）。选它的理由链：中文为主 → 排除纯英文模型；要本地跑 → 排除纯 API 方案；8K 上下文 → 长档案不用截断。

**一个可讲的工程细节**：模型权重是从 ModelScope 下的 FP16 GGUF（19.8MB/s），导入 Ollama 后**校验 sha256 与官方 registry 完全一致**——即我用的和 `ollama pull` 拿到的是同一个文件，只是快了 220 倍。选择 FP16 而非 Q8/Q4 的理由：**量化会改变向量、降低检索质量**。

**索引**：HNSW + `vector_cosine_ops`（余弦距离）。实测验证：同向量距离 0.0000、正交向量 1.0000。

**召回不够怎么办——本项目的答案是"分类处理"：**

`RetrieverService` 是**向量优先 + 结构化兜底**：

```
向量检索有结果 → 直接用
向量检索为空   → ① 按精灵名模糊查（SQL LIKE）
               → ② 名字也没中 → 按属性词查
```

**为什么需要兜底（这是真实数据暴露的问题）**：我实测了短文本的相似度矩阵：

```
                火系精灵   火属性宠物   水系精灵   今天天气不错
火系精灵          1.000      0.731      0.777      0.331
```

「火系精灵」和「水系精灵」（0.777）居然**比**它和「火属性宠物」（0.731）更相似——因为两句共享 4 字里的 3 字（`系精灵`），**模板盖过了属性差异**。结论：**文本越短，嵌入越不稳**。而用户真打「火花」两个字时，向量检索恰好最不可靠，但这恰恰是结构化表能精确回答的。所以兜底不是补丁，是**对嵌入模型能力边界的正面承认**。

**注意一个诚实细节**：兜底路径产出的 `Document` 的 `score` 是 **null**（它没走向量检索，没有相似度）。前端会显示"结构化兜底（无相似度）"——不假装有个分数。

**不相关基线 0.33 的坑**：默认 `similarity-threshold` 配的是 0.5，但 bge-m3 在中文上的分数是"压缩"的。这个阈值**必须用真实语料校准**，不能照搬别人的经验值。dev profile 里我调到了 0.4。

**【未做】** IVF / PQ（pgvector 没实现这些索引类型）；多路召回融合（如 BM25 + 向量的 RRF）；embedding 微调。

**面试可延伸**：HNSW 是图索引，`m`/`ef_construction` 影响召回与内存，查询时 `ef_search` 是召回-延迟的旋钮；IVF 是聚类倒排、PQ 是乘积量化压缩，适合超大规模内存受限场景。pgvector 只提供 HNSW 和 IVFFlat。

### 3.4 Query 理解与改写

**【未做】——这一题必须诚实。** 项目里没有任何 query rewriting / expansion / HyDE / 多 query 生成。

**【已做的替代设计】**：
- `topK` 可由请求覆盖（`ChatRequest.topK`），带兜底逻辑 `topKOrDefault`
- 短 query 的失效用**结构化兜底**而不是改写来兜（见 §3.3）
- **多轮对话未实现**：`ChatRequest` 里有 `sessionId` 字段，但代码注释明确写了"**当前骨架未接多轮记忆，预留给后续的 ChatMemory**"——字段存在但没用。面试时不能说"我做了多轮"。

**面试该怎么答（把"没做"答成"我知道怎么做"）**：
- 指代消解/多轮：把历史轮次压缩成一句"检索用 query"，与原问题分开——**检索用改写后的问题、生成用完整历史**，避免历史污染检索
- 短 query：HyDE（先生成假设答案再拿它去检索）、multi-query（生成 N 个改写并行检索后 RRK 融合）
- 我的项目选择了更便宜的路：**结构化兜底**。理由是领域内专有名词多、"按名字精确查"比"猜用户想问什么"更可靠，且零 API 成本

### 3.5 Rerank 与上下文组装

**【未做 rerank。** 全项目 grep 无任何 rerank / cross-encoder 代码。**】

**【已做】** 上下文组装：
- `renderContext` 按 `[1][2]…` 编号 + `来源：pets/3003.json` + 原文，编号与响应里的 `sources[]` **严格一一对应**
- 出处摘要截断到 120 字（`SNIPPET_MAX_CHARS`），避免响应体被正文撑大
- `sources[]` 带 `spiritName` / `source` / `snippet` / `score`，前端把分数渲染成进度条

**面试该怎么答**：召回和精排是**两个不同的目标**——向量召回优化的是"别漏"（高召回），rerank（cross-encoder）优化的是"排序准"（高精度），因为它让 query 和 doc 做**交叉注意力**而不是各自编码后算余弦。典型做法是召回 topK=50~100 再用 rerank 取 top 3~5 给模型。比例怎么定：看**延迟预算**和**召回曲线的拐点**——先固定召回 100，扫 rerank 后保留数，观察答案质量；本项目没做，但如果做，我会先在现有 5812 条语料上测 recall@k 来确定 k。

### 3.6 生成与幻觉控制

**【已做】——这是本项目做得最扎实的一题，有可复现的证据。**

**约束手段（三层）**：

1. **系统提示词硬约束**（`prompts/rag-system.st` 原文）：
   > 1. 只依据【参考资料】作答。资料中没有的内容，直接说明「资料里没有找到相关内容」，**不要编造**精灵属性、技能数值或进化等级。
   > 2. 引用资料时标注来源编号，例如 [1]、[2]。
   > 3. 涉及数值（种族值、技能威力、命中率、PP、进化等级）时**原样引用，不要四舍五入、不要推测**。

2. **用户提示词二次强调**（`prompts/rag-user.st`）：
   > 请依据上面的参考资料回答问题。若参考资料不足以回答，请**明确指出缺少哪部分信息，不要凭常识补充**。

3. **工程侧**：上下文为空时塞占位符而不是让模型自由发挥；模型故障降级成兜底文案而不是 500。

**检索不到答案时怎么办（实测行为）**：
- 问"深渊罗隐怎么进化？"→ 资料只有前两阶段的进化条件，模型答：**"深渊罗隐是罗隐的第3阶段（首领形态），资料中只给出了前两阶段的进化条件，深渊罗隐本身的进化条件没有记载"** ✓
- 问"test"（无关内容）→ 答：**"资料里没有找到与「test」相关的《洛克王国》内容，无法据此作答"** ✓

**引用可追溯**：`sources[]` 返回来源文件（`pets/3003.json`、`types.json`）、原文摘要、相似度分数；提示词要求模型用 `[n]` 标注，前端把 `[n]` 渲染成角标。

**一个必须提的工程细节**：引用编号的顺序一致性是**正确性问题**，不是显示问题。`renderContext` 和 `toSources` 用的是同一个 `List<Document>` 的同一顺序，代码注释里明确写了"否则等于给出错误引用"。

**【未做】** 生成后的**引用校验**（自动核对答案里的每个断言是否真能在对应 chunk 里找到）——这是我能想到的、这个项目最该补的一环。

### 3.7 系统级优化

**【已做】**

| 项 | 现状 |
| --- | --- |
| 延迟 | 关闭 DeepSeek 思考模式：**3028ms → 717ms（4.2×）**，且 reasoning token 从 336 降到 0；响应把 `retrievalMs` / `llmLatencyMs` / `latencyMs` 分开返回 |
| 成本 | 优先使用 Provider 返回的 `usage`；流式带 `stream_options.include_usage`。`TokenCounter` 只在 usage 缺失时估算并标记 `estimated=true`，不把估算当计费依据 |
| 幂等 | 确定性 id（`UUID.nameUUIDFromBytes(source + "#" + index)`）+ `ON CONFLICT (id) DO UPDATE` → **重复入库是覆盖不是新增**，实测重灌 613 篇后总数仍是 5812 |
| 多级检索 | 向量 + 结构化兜底（见 §3.3），即"先便宜后贵"（本地 SQL 无 API 成本） |
| 多 Provider | 自建 `LlmRouter`：请求指定 → 默认 → `fallback` → 其余可用；每次尝试记录 `success/failed/skipped`、耗时、tokens、错误；同步全部失败返回兜底文案而不 500 |
| 元数据过滤 | **能力已打通未暴露**：`VectorRepository.search(..., filterExpression)` 支持 `elementType == '火' && docType == 'spirit'` 这类表达式，但接口层没开放参数 |
| 流式 | **真流式**：`OpenAiCompatibleProvider.stream` 解析 Provider 的 SSE，`POST /api/chat/stream` 再编码成结构化事件 `meta/delta/done/error`。注意：**检索是订阅后、首 token 前同步做完的**，所以首字延迟包含检索时间 |
| 可观测性 | `GET /api/llm/metrics` 提供进程内请求数/成功失败/token/平均最大耗时；`ChatResponse` 与 `done` 事件带 attempts。**没有** Prometheus、trace、P95/P99 或持久化 |

**【未做】** 缓存（无 `@Cacheable`、无 query 缓存）；难度路由（没有按问题难度选模型；现在的路由只做可用性与失败降级）；增量更新（ingest 是 `replaceAll` **全量重建**，靠幂等 id 保证安全但不高明）；权限过滤（单用户场景）；生产级可观测性（无 trace、无持久化指标、无 P95/P99）。

**面试可延伸**：延迟拆解为"嵌入 + 检索 + 生成"三段分别优化；缓存分三层（query→答案、query→向量、doc→向量）；增量更新要用 doc 级 hash 比对 + 软删除而不是全量重建；权限过滤要在**检索阶段**做（pgvector 的 `WHERE metadata->>'owner' = ANY(...)`）而不是生成后过滤，否则会通过"资料里有但你无权看"泄漏。

---

## 4. 对照【二、AI 应用调优实战经验】

### 4.1 调优方法论（本项目最有说服力的部分）

**我的定位方法：把链路拆成可独立验证的层，用数据核对，不靠最终答案猜。**

三层验证手段：
1. **只看检索层**——直接用 SQL 查 `vector_store`、跑 `embedding <=> ?` 排序，绕开 Spring AI 和模型
2. **只看数据层**——查关系表的 count、`array_to_string` 展开数组字段，核对"模型说的"和"库里写的"是否一致
3. **看日志里的 SQL**——dev profile 打开 `org.springframework.jdbc.core: DEBUG`，能拿到 Spring AI 真正执行的语句

**一个贯穿始终的教训：不相信"成功"的输出。** 三个同源的例子：
- `DISM /RestoreHealth` 跑满 **40.6 分钟**、报告"操作成功完成"，但卡死的服务事务指标**纹丝不动**
- 我用正则改 `application.yml` 时吃掉一个换行，`init:` 变成注释、子键降级挂错层级——**文件仍是合法 YAML，Spring 不报任何错**，配置静默失效
- `pg_ctl` 报告"服务器进程已经启动"，但数据库其实早已被外部终止

**怎么区分是检索问题 / prompt 问题 / 模型问题 / 数据问题**（本项目的真实判据）：

| 症状 | 先看哪里 | 本项目的判据 |
| --- | --- | --- |
| 答"资料里没有"，但数据里明明有 | **数据/索引** | 如果向量检索命中数正常但内容不对 → 索引内容问题；如果命中为空 → 检索或语料问题 |
| 命中内容对，但答案错 | **prompt / 模型** | 检查 prompt 里的资料段落是否真的包含那个事实 |
| 答得含糊、数值四舍五入 | **prompt** | 提示词里有没有写"原样引用，不要四舍五入" |
| 检索命中空 + 兜底也空 | **语料覆盖** | 该实体根本没入库（如 452 只未实装精灵） |

### 4.2 四个真实缺陷案例（带数据，可直接当 STAR 素材）

**案例 1：一批 500，整块语料没进库 —— 用"数字巧合"定位失败点**

- **现象**：全量入库接口返回 500（`{"detail":"服务内部错误"}`），无更多信息
- **定位**：不猜，直接查库 count → `vector_store = 5628`。而 613（精灵）+ 475（技能）+ 4540（道具）= **5628**，精确相等 → **失败点在下一个分支（reference）**，且 `element_type = 0` 印证
- **根因**：`element_type` 表有 4 个 `text[]` 列，我把数组当**字符串参数**绑定。Postgres 的 `text[]` 不接受 varchar 参数
- **修复**：SQL 里写 `?::text[]` 显式转换（一个词的改动）
- **影响**：是它导致 reference 的 107 条 + wiki 的 77 条一个都没进库。修复后 5812 条与 dryRun 预测值**完全一致**

**案例 2：模型反过来纠正了我的数据缺陷 —— legacy_moves 绑定层级错**

- **现象**：问"火花"时，模型在答案里写：**"血脉技能 18 项在资料中均显示为 null，无有效内容"**
- **定位**：模型说得对。`legacy_moves` 的真实结构是 `{monster_id, type_id, move_id, move}` 的**包装对象**，技能在里面的 `move` 字段；我把外层直接绑成了 `Skill`，所以每个字段都是 null
- **影响**：**613 篇档案 × 18 行 "null"** —— 既浪费 token，又污染嵌入，还误导模型
- **修复**：新增 `LegacyMove` record + `legacyMoveSkills()` 取真正的技能
- **验证（数据层面，不是"看起来好了"）**：
  - `SELECT count(*) FROM vector_store WHERE content LIKE '%- null%'` → **0**（修复前每篇 18 行）
  - `spirit_skill` 关联数 **18420 → 29436**，`skill` 表 **970 → 976**
  - 重新提问，18 个血脉技能（含属性与威力）全部正确列出
  - **并加了回归断言**：解析真实数据后，血脉技能名不得为 null

**案例 3：一个答不出来的问题，暴露了数据建模缺口 —— 属性克制的反向关系**

- **现象**：问"火系克制哪些属性？"，模型答**"资料里没有找到直接内容，现有资料只给出了火系的防御面信息"**
- **定位**：模型分析完全正确。原始 `types.json` 只给**防御面**（`vulnerable_to` = 我怕谁、`resistant_to` = 我抗谁），而"我克制谁"是**同一关系的反向读法**——某属性 U 的 `vulnerable_to` 里含 Fire，就等于"火克制 U"。**数据里有答案，只是我的索引没表达出来**
- **修复**：入库时为每个属性补两个派生字段（`strongAgainst` / `resistedBy`，即反向遍历 19 个属性）
- **验证**：修复后 1.3 秒答对，并把模型的每一项断言与数据库**逐项核对**

| 模型回答 | 数据库实测 | |
| --- | --- | --- |
| 地系弱点：武、水、机械、冰、草 | `Fighting,Water,Mechanical,Ice,Grass` | ✓ |
| 地系抵抗：火、电、普通、翼、毒 | `Fire,Electric,Normal,Flying,Poison` | ✓ |
| 地系克制：火、冰、电、毒 | `Fire,Ice,Electric,Poison` | ✓ |
| 攻击被抵抗：草、武 | `Grass,Fighting` | ✓ |
| 火系克制：草、冰、虫、机械 | `Grass,Ice,Bug,Mechanical` | ✓ |

> **这一段是"零幻觉"的硬证据**：不是"听起来对"，而是**与源数据逐项吻合**。

**案例 4：两类静默的配置缺陷 —— 用测试固化不变量**

- **缺陷 A**：我配了 `num-batch: 32`，以为它控制"一次请求带几条文本"。实际它控制**模型内部 prompt 处理的批大小**（默认 512），配小反而降低 GPU 吞吐
- **缺陷 B**：用正则改 YAML 时吃掉换行 → `init:` 整段变注释、子键降级挂到 `embedding` 下 → **静默失效**
- **防护**：`ApplicationConfigTest`（11 条）把不变量写成断言：
  - `spring.ai.model.chat=none`，避免 Ollama starter 额外装配一个 ChatModel；embedding 仍是 ollama
  - `locke.ai.llm.provider` / `fallback` / `providers.deepseek|tongyi` 必须存在
  - **跨文件不变量**：`EMBEDDING_DIMENSIONS` 的默认值 == `V1__init_schema.sql` 里 `vector(N)` 的 N
  - `api-key` 必须是占位符、不得出现 `sk-` 明文（防误提交）
  - DeepSeek 模型名必须落在 API 认识的 id 里
  - `extra-body.thinking.type` 必须是 `disabled`

### 4.3 Prompt 与模型调优

**【已做】**
- Prompt 是**模板文件**（`prompts/*.st`）而不是硬编码在 Java 里——改提示词不用改代码
- 提示词内容极简（系统 6 条规则、用户 3 行），刻意避免堆砌
- **模型选型的实测依据**：`deepseek-flash` **默认开启思考模式且 effort=high**，实测 3028ms、输出里 336/355 是推理 token；改成 disabled 后 717ms。**另一个静默坑**：官方文档明确"思考模式下 `temperature`、`presence_penalty`、`frequency_penalty` 会被忽略，且不报错"——所以我原本配的 `temperature: 0.3` 一直是空转的，关掉思考模式后才真正生效
- **provider 可切换**：`LlmProperties` 描述 Provider，`LlmProviderRegistry` 启动时装配，`LlmRouter` 按“请求指定 → 默认 → fallback → 其余可用”选择并降级。这比 `@ConditionalOnProperty` 更进一步：不用重启、不用改 `spring.ai.model.chat`，请求体里的 `provider` 就能切换，失败还会自动尝试下一个。所有 Provider 都走 OpenAI-compatible `/chat/completions`，DeepSeek 与 DashScope 共用一套传输实现

**【未做】** Prompt 的版本管理与 A/B；防止 prompt 过拟合评测集（本项目连评测集都没有）。

**面试可答**：prompt 迭代要**一次只改一个变量**并记录 diff；避免过拟合的办法是**评测集与迭代集分离**，且不要针对个例往 prompt 里堆补丁（那是用 prompt 打补丁掩盖数据问题——本项目案例 3 就是反面教材：正确解是补索引，不是改 prompt 教模型"自己反推属性关系"）。

### 4.4 工程与产品调优

**【未做】** 用户反馈收集、灰度、A/B、回滚、线上止损——**这是单人本地项目，没有线上流量**。

**【已做的工程实践】**：分层日志（`com.locke.ai: DEBUG`、SQL DEBUG）、`LlmException`/HTTP 状态区分 retryable（超时/408/409/429/5xx）与不可重试（401/404）、`LlmRouter` 记录每次 attempt 并自动降级而非直接 500、SSE 用结构化事件而不是裸文本、进程内指标接口、确定性 id 保证重复入库安全。

**面试可答**：线上止损的顺序是——**先切流量/降级，再定位**。RAG 应用的降级阶梯：完整 RAG → 只检索不生成（返回原文片段）→ 直接返回兜底话术；回滚粒度要细到"提示词版本 + 索引版本 + 模型版本"三者可独立回滚，因为效果变差可能是三者中任意一个变了。

---

## 5. 对照【三、AI 评测体系搭建与落地】

### 5.1 诚实的现状：**评测集和评测指标都没有**

本项目**没有**评测集、没有黄金集/对抗集、没有指标、没有 LLM-as-Judge、没有人工标注规范、没有离线/在线对齐。**面试时绝对不能说有。**

**已做的替代品（可以讲，但要讲清定位）**：
- **45 个自动化测试：40 通过、5 跳过**（覆盖配置契约、真实数据解析、入库编排、API 端到端、Provider 降级、SSE 事件）——这是**软件质量测试**，不是**效果评测**；跳过的 5 个需要 `-Dlocke.it=true` + PostgreSQL
- **11 条配置契约测试**守住结构性不变量（如三处维度一致、密钥不入库、`spring.ai.model.chat=none`）
- **人工抽检 + 数据核对**：把模型答案的每一项断言与数据库逐项比对（§4.2 案例 3 那张表）——这是**最原始但最可靠的人工评测**

### 5.2 如果要做，我会这样落地（面试重点讲这个）

**评测集构建**
- **来源**：① 从真实用户问法里收集（本项目可从游戏社区高频问题入手）；② 基于语料**反向生成**（给模型一个 chunk，让它出题，答案必须能在该 chunk 里找到 → 天然带标准答案和标准出处）；③ 专家/自己手写边界问题
- **规模**：起步 100~200 条足够定位问题（不要一上来追求几千条）
- **分层**：
  - **黄金集**：日常回归，稳定不变，50~100 条
  - **对抗集**：语料里**没有**的问题（测它会不会编）、相似实体混淆（火花/焰火/火神）、超短 query、错别字
  - **边界集**：空语料、单字 query、超长 query、多轮指代
- **防泄漏**：黄金集**只用于评测不用于调试**；prompt 迭代时用"迭代集"，改完再在黄金集上验收；禁止把评测问题写进 prompt 示例

**指标（分三层，这是 RAG 评测的关键）**

| 层 | 指标 | 本项目可怎么测 |
| --- | --- | --- |
| **检索层** | recall@k、precision@k、MRR、命中率 | 因为每篇文档都有确定来源（`pets/3003.json`），可以造"问题→应命中文档 id"的标注，直接算 recall@k |
| **生成层** | 忠实度（faithfulness）、答案相关性、引用正确率 | 引用正确率可以**自动**测：答案里的每个 `[n]` 对应的 chunk 是否真的包含该断言 |
| **端到端** | 答案正确率、拒答准确率（该拒答时拒答、不该拒答时不拒答）、延迟、成本 | 拒答准确率对本项目特别重要，因为提示词把"不知道就说不知道"当硬约束 |

**自动 + 人工结合**
- 自动（LLM-as-Judge）：**适合** faithfulness、相关性这类"判断型"指标，成本低可回归；**不适合**数值正确性（会算错、会被流畅的错答案骗过）。而且 judge 自己会漂移 → 必须**定期用人工标注校准 judge**，并固定 judge 模型与 prompt 版本
- 人工：只标注**抽样**的边界样本；标注规范要写成"可判定的问题"（例："答案中的每个数值是否能在给定 chunk 里原文找到？是/否/部分"），而不是"回答好不好"
- **本项目已有的天然优势**：数值类问题可以**完全自动**判对错（和数据库比对），根本不需要 judge

**评测闭环**
- 每次迭代（改提示词/换模型/调 topK/改 chunk 策略）**只动一个变量**，跑同一套黄金集，比较指标
- **判断"是否有效提升"要预设阈值和置信度**：100 条样本上 ±5% 的波动是噪声，不是提升
- **离线与在线对齐**：离线指标涨了但线上没涨，说明离线集不代表性；做法是从线上真实 query 里**持续采样**补充评测集

---

## 6. 能力边界总表（一页纸，面试前扫一遍）

| 能力 | 状态 | 备注 |
| --- | --- | --- |
| RAG 完整链路（离线索引 + 在线检索） | ✅ | 端到端跑通，45 测试（40 通过 / 5 跳过） |
| 结构化/按实体的 chunk 策略 | ✅ | 613 精灵 = 613 chunk，最长 1904 字 |
| Token 级切分（长文档兜底） | ✅ | 数据字典 20 文档 → 77 chunk |
| 嵌入模型选型 + 本地 GPU 部署 | ✅ | bge-m3 FP16，sha256 与官方一致 |
| HNSW + 余弦距离 | ✅ | 实测同向量 0、正交 1 |
| 混合检索（向量 + 结构化兜底） | ✅ | 兜底 `score=null`，不假装有分 |
| 幻觉控制（提示词硬约束） | ✅ | 有实测的"如实拒答"行为 |
| 引用与可追溯 | ✅ | 编号与 sources 严格对应 |
| 幂等入库 | ✅ | 确定性 id + upsert |
| 真流式输出 | ✅ | 自建 OpenAI-compatible SSE + `meta/delta/done/error`；检索在首 token 前完成 |
| 多 provider 抽象 | ✅ | DeepSeek / 通义运行期切换，失败按指定→默认→fallback→其余降级 |
| SSE 结构化事件 | ✅ | `meta/delta/done/error`；`done` 带 usage、分段耗时、attempts |
| Token / 耗时上报 | ✅ | 优先 usage，缺失才估算；`retrievalMs/llmLatencyMs/latencyMs` 分开 |
| 元数据过滤 | ⚠️ 已打通未暴露 | `filterExpression` 支持，接口无参数 |
| **Rerank** | ❌ | 无任何 cross-encoder 代码 |
| **Query 改写 / HyDE / 多 query** | ❌ | 完全没做 |
| **多轮对话记忆** | ❌ | `sessionId` 字段预留，注释写明未接 |
| **缓存** | ❌ | 无 |
| **难度路由** | ❌ | 无按问题难度选模型；现有路由只做可用性与失败降级 |
| **增量更新** | ❌ | 全量 `replaceAll` 重建 |
| **权限过滤** | ❌ | 单用户场景 |
| **生产级可观测性** | ⚠️ 部分 | 只有进程内聚合指标 + 日志；无 trace、无持久化、无 P95/P99 |
| **评测集 / 指标 / LLM-as-Judge** | ❌ | 只有 45 个软件质量测试 |
| **A/B / 灰度 / 回滚** | ❌ | 无线上流量 |

---

## 7. 面试时最容易被追问的点

### 7.1 "你怎么保证模型不瞎编？"

三层答：① 系统提示词把"没有就说没有"写成第 1 条硬约束，并明确禁止编造数值；② 用户提示词二次强调"不要凭常识补充"；③ 工程上，上下文为空时塞占位符而不是放任模型自由发挥，并且模型故障时降级成兜底文案而不是让接口 500。

**证据**：问一个资料里只有一半答案的问题（深渊罗隐的进化条件），它答出了有依据的部分，并明确指出缺哪部分。

### 7.2 "你这个 RAG 最大的问题是什么？"

**索引表达力不足 + 没有排序精排。** 案例 3 就是典型：数据里其实有"火系克制什么"的答案，但我的索引只存了防御面，模型只能如实说没有。这说明**RAG 的上限由索引决定，不是由模型决定**。第二是只做了向量召回、没有 rerank，top-k 里的顺序完全由余弦相似度决定。

### 7.3 "为什么不用 Docker？（如果面试官问部署）"

开发机的 Windows 服务栈坏了：`WinSxS\pending.xml` 有 50.8MB 的卡死服务事务（正常几百 KB），导致 `VirtualMachinePlatform` 永久停在 `EnablePending`、`vmcompute.exe` 从未落盘。已尝试启用特性 + 三次重启 + disable/enable 重装 + `DISM /RestoreHealth`（跑 40.6 分钟报告成功但指标不变）——全部无效。

**处理方式**：改用原生 PostgreSQL + 原生 Ollama 完全绕开虚拟化，项目代码一行未改。**这个问题本身也是个可讲的排查故事**：症状（`wsl --status` 报未启用虚拟化、`docker ps` 报 500）与真因（组件服务事务卡死）隔了好几层，中间我一度错误归因为"缺一次重启"。

### 7.4 "你怎么知道检索效果好不好？"

诚实答：**目前靠人工抽检 + 与数据库逐项核对**，没有量化指标。这是这个项目最该补的一环，如果继续做，我会先建 100~200 条带标准出处的评测集（利用"每篇文档都有确定来源"这个天然优势，可以直接算 recall@k），把检索层和生成层分开评。

### 7.5 面试官可能问的"陷阱题"

- **"你的 chunk overlap 设的多少？"** → 如果你基本不切分，overlap 就没有意义。**直接说明"我的策略是按实体渲染，613 只精灵一条都没被切开"，比编一个数字好。**
- **"你用了什么 rerank 模型？"** → 没做。答"没做，但我知道它的定位是召回后的精度精排，典型是召回 50~100 再 rerank 取 3~5"。
- **"多轮怎么处理的？"** → 没做。`sessionId` 字段预留了，注释里写明未接 ChatMemory。

---

## 8. 环境与运维备忘（下个对话可能用到）

| 项 | 值 |
| --- | --- |
| 项目路径 | 仓库根目录 `locke-kingdom-rag` |
| 数据库 | `localhost:5432` / 库 `locke` / 用户 `postgres` / 密码 `postgres` |
| **数据库管理方式** | **Windows 服务 `postgresql-17`（自启）**，`Start-Service` / `Stop-Service` |
| PG 安装位置 | `<PG_HOME>`（免安装版 17.11）；pgvector 0.8.6 的 dll 装进 `lib` + `share\extension` |
| Ollama | `localhost:11434`，模型 `bge-m3:latest` |
| 应用 | `localhost:8080`，主页 `http://localhost:8080/`（`src/main/resources/static/index.html`） |
| 语料根目录 | `<DATA_ROOT>`（`locke.ai.ingest.data-root` / 环境变量 `LOCKE_DATA_ROOT`） |
| 密钥 | `.env`（已 gitignore）；`application.yml` 只有 `${DEEPSEEK_API_KEY}` / `${DASHSCOPE_API_KEY}` 占位符；通义 key 可选 |
| Provider | 默认 `deepseek`，fallback `tongyi`；请求体 `provider` 可覆盖；目录见 `GET /api/llm/providers` |
| 凭据优先级坑 | `application-dev.yml` **硬编码**了数据源，会压掉 `application.yml` 里的 `${DB_*}` 占位符 → 改 `.env` 的 `DB_*` 在 dev 下**不生效**（实测证据：`top-k` 基础配 5、dev 配 3，运行时观察值 = 3） |
| 启动日志 | `target/app.log`（用 Start-Process 重定向时）；IDEA 里直接看控制台 |
| JDK | 用 21 LTS。Boot 4.1.1 支持 17~21，JDK 27 这类太新的版本会直接超出支持范围 |

**几个必须记住的操作陷阱**（都实际踩过）：

| 陷阱 | 症状 | 正确做法 |
| --- | --- | --- |
| psql `-c` 传中文 SQL | `invalid byte sequence for encoding "UTF8"` | 写进 UTF-8 文件用 `psql -f` |
| PowerShell 内联 JSON 发请求 | 引号被改写 → 服务端收到坏 JSON → 500 | 写进 UTF-8 文件用 `curl --data-binary @file` |
| `pg_ctl start` 接进 PS 管道 | 命令永久挂起（postgres 继承管道句柄） | 输出重定向到**文件**，或不接管道 |
| `Start-Process -Wait` 拉起 `pg_ctl` | 永久挂起（等整个进程树，postgres 不退出） | 不要 `-Wait`，改轮询端口 |
| PowerShell 变量名不分大小写 | `$SVC` 与 `$svc` 是同一个变量，被覆盖成对象 | 变量名别只靠大小写区分 |
| `$src\**\*.java` 当递归用 | 两层以上的包被漏掉，grep 假阴性 | 用 `Get-ChildItem -Recurse` |
| 杀"卡住的后台作业" | **postgres 继承了被杀的 shell 的句柄 → 数据库被终止**（靠 WAL 崩溃恢复，未丢数据） | 杀进程前先确认目标进程的句柄依赖 |

---

*本文所有数字均来自本地实测。标注"未做"的部分在面试中请如实回答——一个能清楚说出自己系统边界的人，比一个把没做的说成做了的人可信得多。*
