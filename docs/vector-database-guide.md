# 向量数据库与调用：以本项目的 pgvector 为例

这份文档的目标是让你**真正理解**向量数据库在做什么，以及本项目的代码是怎么调用它的。
每一节都对应项目里的真实代码或可执行的命令，不是泛泛的概念介绍。

阅读顺序建议：先看 §1 建立直觉，§2~§4 理解原理，§5~§6 看本项目怎么用，§7 开始动手，
§8 是踩坑清单，§9 用来自测。

---

## 1. 为什么需要向量数据库

### 1.1 关键词检索的硬伤

假设用户在库里搜「深渊罗隐怎么进化」。

传统做法是 `WHERE content LIKE '%深渊罗隐%'`。它能工作，但有两个问题：

1. **写法稍有不同就搜不到**。用户打「罗隐的进化条件」，你库里存的是「深渊罗隐」，
   `LIKE '%罗隐%'` 能命中（因为「罗隐」是子串），但用户打「罗隐 进化」或者
   「那个地系的终形态」，关键词就崩了。
2. **你没法按「意思」排序**。命中了 20 条，哪条最相关？`LIKE` 只能按字母序或时间序给你。

本项目的兜底逻辑正是利用了「关键词能命中子串」这一点（`SpiritRepository.searchByDisplayName`
用了 `ILIKE '%…%'`），但它只能解决最简单的场景。

### 1.2 向量检索换了个思路

把每段文本映射成坐标系里的一个点（向量）。**意思相近的文本，落点也相近。**

于是「检索」变成了「找最近的几个点」——这是一个纯数学问题，可以用索引加速。

具体到本项目：

| 用户问题 | 库里的文档 | 关键词重合度 | 向量距离 |
|---|---|---|---|
| 深渊罗隐怎么进化 | 深渊罗隐 / 进化链：阿米亚特 ← 等级达到 30 级… | 高 | 很近 |
| 那个地系的终形态 | 深渊罗隐 / 属性：地 / 第3阶段（首领形态） | **零** | 近 |
| 洛克贝怎么获得 | 道具：洛克贝 / 获取方式：… | 中 | 近 |

第二行就是关键词检索完全做不到、而向量检索能做到的场景。

---

## 2. 三个核心概念

### 2.1 嵌入（Embedding）

嵌入模型的作用是：**文本 → 固定长度的浮点数组**。

本项目用的是 `bge-m3`，它输出 **1024 个浮点数**：

```
"火系精灵"  →  [0.0231, -0.0417, 0.0089, ..., 0.0155]   ← 1024 个数
"火属性宠物" →  [0.0244, -0.0401, 0.0092, ..., 0.0161]   ← 和上面很接近
"水系精灵"  →  [-0.0388, 0.0121, -0.0276, ..., 0.0034]   ← 差得远
```

三件必须记住的事：

1. **只有同一个模型产生的向量才能互相比较。** 不同模型的向量不在同一个坐标系里，
   就像拿「经纬度」和「极坐标」比大小，数值上能算，意义上毫无关系。
2. **维度必须一致。** bge-m3 是 1024 维，你换成某个 768 维的模型，
   已经存进库的向量全部作废，而且**数据库会直接拒绝写入**（列类型是 `vector(1024)`）。
3. **嵌入模型不产生文本。** 它只会把文本变向量。本项目里
   `bge-m3`（本地 Ollama）负责向量化，**DeepSeek 负责生成回答**——两个完全独立的模型。

### 2.2 距离度量：怎么算「近」

最常见的是**余弦相似度**：只看两个向量的**方向**是否一致，不管长度。

```
cos(A, B) = (A · B) / (|A| × |B|)        取值范围 [-1, 1]，1 表示方向完全相同
```

pgvector 提供了三个操作符：

| 操作符 | 含义 | 什么时候用 |
|---|---|---|
| `<=>` | 余弦距离 = `1 - 余弦相似度` | 文本语义检索（**本项目用这个**） |
| `<->` | 欧氏距离（直线距离） | 向量已归一化时和余弦等价 |
| `<#>` | 负内积 | 性能优先、向量已归一化 |

**这里有个极易搞混的地方，务必看清：**

- pgvector 的 `<=>` 是**距离**：**越小越相似**（0 表示完全一样）
- Spring AI 的 `SearchRequest.similarityThreshold` 是**相似度**：**越大越相似**

Spring AI 内部会把它们换算过来（大约是 `距离阈值 = 1 - 相似度阈值`，
然后 SQL 里写 `WHERE embedding <=> ? < 距离阈值`）。所以你在代码里写
`similarityThreshold(0.5)` 的意思是「只要相似度高于 0.5 的」，
在 SQL 层面变成「只要距离小于 0.5 的」。

> 顺带说：`vector_cosine_ops`（建索引用）和 `<=>`（查询用）必须成对出现，
> 用错了索引不会生效，查询会退化成全表扫描——不报错，只是慢。

### 2.3 索引：为什么不能每次全表扫

最朴素的检索是：把查询向量和表里**每一行**都算一次距离，排序取前 k 个。
这叫**暴力检索**（exact search），结果最准，但数据量大时慢得没法用。

pgvector 支持两种近似索引（ANN，Approximate Nearest Neighbor）：

| 索引 | 原理 | 特点 |
|---|---|---|
| **HNSW** | 多层图结构，从稀疏的上层快速逼近 | 查得快、召回率高，**内存占用大**，建索引慢 |
| IVFFlat | 先聚类成若干桶，只在最近的几个桶里找 | 内存小，但**需要表里已有数据**才好用 |

本项目用 **HNSW**：

```sql
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops);
```

两个必须知道的限制：

1. **HNSW 在 pgvector 里最多支持 2000 维。** 所以如果你换成 3072 维的模型，
   要么改用 IVFFlat，要么降维。
2. **近似意味着可能漏召。** HNSW 有小概率漏掉真正最相似的那条。
   对问答场景完全可接受，但对「必须 100% 精确」的场景不合适。

---

## 3. pgvector 是怎么把向量塞进 Postgres 的

pgvector 是一个 Postgres 扩展，它做了三件事：

1. **引入 `vector` 数据类型**——原本 Postgres 没有能存浮点数组并按距离排序的类型
2. **引入距离操作符**（`<=>` `<->` `<#>`）和对应的索引支持
3. 让 `ORDER BY embedding <=> ? LIMIT 10` 这种写法能用上索引

### 3.1 启用扩展

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

这一句之后，`vector(1024)` 才能当作列类型用。**没有它，建表就报错。**

### 3.2 本项目的表结构

摘自 `src/main/resources/db/migration/V1__init_schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,               -- 原始文本（就是喂给嵌入模型的那些字）
    metadata  json,               -- 元数据：来源、精灵名、属性、块序号…
    embedding vector(1024)        -- 1024 维向量，维度写死在列定义里
);
```

逐列说明：

- **`id`**：文档的唯一标识。**本项目由代码生成确定性 UUID**（见 §6.1），
  这是实现「重复入库不产生重复数据」的关键。
- **`content`**：存原文。检索命中后，这段文本会被拿去拼 prompt 喂给大模型。
  **所以内容质量直接决定回答质量**，这也是为什么我们要把结构化的 JSON 渲染成
  人话（`SpiritChunkSplitter`），而不是把字段名和数字直接塞进去。
- **`metadata`**：JSON 字段，用来放「这条属于哪只精灵、什么属性、来自哪个文件」。
  检索时可以用来过滤（例如只查火系），回答时用来标出处。
- **`embedding vector(1024)`**：**维度是列定义的一部分，改维度要改列**。
  这是本项目里「必须同时改三处」的那个 1024：
  `EMBEDDING_DIMENSIONS` ↔ `pgvector.dimensions` ↔ SQL 里的 `vector(1024)`。

### 3.3 为什么 `initialize-schema: false`

Spring AI 的 PgVectorStore **自己也会建表**（配置项 `initialize-schema`）。
本项目把它关掉，让 **Flyway 独占 DDL 权限**：

```yaml
spring.ai.vectorstore.pgvector.initialize-schema: false
```

理由：两套建表逻辑并存时，定义容易不一致（比如它建 `metadata json`、
你建 `jsonb`，或者索引名不同），排查起来很烦。**一个项目里 DDL 只能有一个来源**，
这条规矩值得你在任何项目里坚持。

---

## 4. Spring AI 的抽象层：谁在调谁

Spring AI 把「向量检索」抽象成四个东西，理解这四个就理解了这个框架：

| 接口 | 职责 | 本项目的来源 |
|---|---|---|
| `EmbeddingModel` | 文本 → `float[]` | 由 `spring-ai-starter-model-ollama` 自动装配 |
| `Document` | 文本 + 元数据 + id 的载体 | 项目自己 `Document.builder()` 构造 |
| `VectorStore` | 写入 / 检索 / 删除 | 由 `spring-ai-starter-vector-store-pgvector` 自动装配 |
| `SearchRequest` | 检索参数（query / topK / 阈值 / 过滤） | 项目自己 builder 构造 |

**关键理解：`VectorStore` 内部持有一个 `EmbeddingModel`。**
你调 `vectorStore.add(documents)` 时，它自动帮你把文本嵌入成向量再写库；
你调 `similaritySearch(request)` 时，它自动把 query 嵌入成向量再去比。
你从头到尾不需要手动碰 `float[]`。

### 4.1 本项目的真实调用链

```
POST /api/chat  { "question": "深渊罗隐怎么进化？" }
 │
 ├─ ChatController.chat(request)
 │
 ├─ ChatService.ask(request)
 │   │
 │   ├─ RetrieverService.retrieve(question, topK=5, threshold=0.5)
 │   │   │
 │   │   ├─ VectorRepository.search(question, 5, 0.5)
 │   │   │   └─ vectorStore.similaritySearch(
 │   │   │          SearchRequest.builder()
 │   │   │              .query("深渊罗隐怎么进化？")
 │   │   │              .topK(5)
 │   │   │              .similarityThreshold(0.5)
 │   │   │              .build())
 │   │   │        │
 │   │   │        ├─ ① EmbeddingModel.embed("深渊罗隐怎么进化？")
 │   │   │        │     └─ HTTP POST http://localhost:11434/api/embed  → 1024 维向量
 │   │   │        │
 │   │   │        └─ ② SQL: SELECT ... ORDER BY embedding <=> ?::vector LIMIT 5
 │   │   │              └─ 命中 5 条 Document（含 content 和 metadata）
 │   │   │
 │   │   └─ 如果 ① 返回空 → 回退到 SpiritRepository 的 SQL 关键词查询
 │   │
 │   ├─ PromptBuilder.buildUserPrompt(context, question)
 │   │     → 【参考资料】[1] 来源：pets/13000169.json … 【用户问题】…
 │   │
 │   └─ LlmRouter.complete(request, provider)
 │         ├─ 选择 Provider（指定 → 默认 → fallback → 其余可用）
 │         └─ HTTP POST <base-url>/chat/completions  → 自然语言答案 + usage
 │
 └─ ChatResponse { answer, sources[], provider, model,
                   promptTokens, completionTokens, totalTokens,
                   latencyMs, retrievalMs, llmLatencyMs, fallback, attempts[] }
```



对照代码看这几步：

- `RetrieverService.retrieve` — `src/main/java/com/locke/ai/service/RetrieverService.java`
- `VectorRepository.search` — `src/main/java/com/locke/ai/repository/VectorRepository.java`
- `ChatService.ask` — `src/main/java/com/locke/ai/service/ChatService.java`

**注意 ① 和 ② 是两次完全独立的网络/数据库操作**，一个走 Ollama，一个走 Postgres。
排查「检索不准」时，先确定是嵌入出了问题（①）还是排序/过滤出了问题（②）。

---

## 5. 一条向量到底经历了什么：写入到检索

### 5.1 写入路径

入库时，每个 chunk 是这样进库的：

```
SpiritChunkSplitter 渲染出「精灵档案」文本（约 1400 字）
    ↓
DocumentEnricher 补上 metadata 和确定性 id
    ↓
VectorStore.add(documents)
    ├─ EmbeddingModel.embed(documents)      ← 批量调 Ollama，得到 N×1024 向量
    └─ SQL（PgVectorStore 内部，大致形状）：
         INSERT INTO vector_store (id, content, metadata, embedding)
         VALUES (?, ?, ?::jsonb, ?)
         ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::jsonb, embedding = ?
```

`ON CONFLICT (id) DO UPDATE` 就是**幂等**的来源：同一个 id 再次写入是**覆盖**而不是新增。
所以你可以放心地反复跑 `./scripts/ingest.sh`。

### 5.2 检索路径

```
用户问题
    ↓
EmbeddingModel.embed(question)              ← 一次 Ollama 调用
    ↓
  SELECT *, embedding <=> ? AS distance
  FROM public.vector_store
  WHERE embedding <=> ? < ?
  ORDER BY distance LIMIT ?
    ↓
5 条 Document → 拼进 prompt 的【参考资料】段落
```

> **上面这条 SQL 不是示意，是从一次真实运行的 DEBUG 日志里原样抓下来的。**
> 把 `org.springframework.jdbc.core` 的日志级别设为 `DEBUG`（本项目 dev profile 已经开了）就能在
> 控制台看到 Spring AI 到底发了什么 SQL。
>
> 有个容易看漏的细节：**查询向量被绑定了两次**。第一次是 SELECT 里算 `distance`，
> 第二次是 WHERE 里做阈值过滤——因为 pgvector 的 `<=>` 在两个位置都要出现。
> 第三个参数是距离阈值（由 `similarityThreshold` 换算而来），第四个是 `topK`。

### 5.3 想亲眼看这些 SQL？不用猜

本项目 `application-dev.yml` 里已经打开了 SQL 日志：

```yaml
logging:
  level:
    org.springframework.jdbc.core: DEBUG
```

启动应用（默认就是 dev profile）后，控制台会把**实际执行的 SQL 和参数**打出来。
这比看任何文档都可靠——**学框架最好的方法就是让它把 SQL 打出来**。

---

## 6. 本项目两个值得学的设计

### 6.1 为什么 id 必须由自己生成，而且必须是确定性的

这是本项目里最「反直觉但重要」的一处设计。

Spring AI 的 `TextSplitter` 在切分文档时会自己造 id，行为不保证：

- 如果它让**同一个文档切出的多块共用同一个 id**，那么 `ON CONFLICT (id) DO UPDATE`
  会把多块**合并成一行，静默丢数据**——不报错，你只会发现召回变少、答案变差；
- 如果它在 id 后面**拼后缀**（`原id-0`、`原id-1`），而 `vector_store.id` 是 `uuid` 类型，
  写库时会直接报 `invalid input syntax for type uuid`。

所以本项目的做法是：**把 id 的生成放到流水线的最后一步，由自己掌控**。
见 `DocumentEnricher.stableId`：

```java
public static String stableId(String source, int index) {
    String seed = (source == null ? "unknown" : source) + "#" + index;
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
}
```

用「来源 + 块序号」作种子生成 name-based UUID：

- **同输入必同输出** → 重复入库是覆盖，行数不涨
- **一定合法** → 永远是标准 UUID，`uuid` 列不会报错
- **一定唯一** → 不同精灵的 source 不同，同一精灵的不同块序号不同

> 已知缺口：如果某个文件改**短**了，多出来的旧块会留在库里（它占着 id 但不在本轮写入集合里）。
> 补法是入库前按 source 删除，见 `VectorRepository` 里的注释。

### 6.2 向量检索不该是唯一的一条腿

`RetrieverService.retrieve` 的逻辑是：

```java
List<Document> hits = vectorRepository.search(question, topK, threshold);
if (!hits.isEmpty()) {
    return hits;                        // 向量命中，直接用
}
// 向量没命中 → 回退到结构化 SQL 查询（按精灵名 / 按属性）
return keywordFallback(question, topK);
```

为什么要这样？**短问题 + 专有名词是向量检索的弱项。**
用户打「火花」两个字，语义信息太少，很容易被同属性的其它精灵挤掉；
但「火花」在结构化表里是一次精确的 SQL 查询，100% 命中且没有 API 成本。

**经验：RAG 系统里，能让 SQL 精确回答的问题就不要交给向量检索。**
向量检索擅长的是「怎么打某关」「这个机制是什么意思」这类语义问题。

---

## 7. 动手实验（照着跑，每步都能看到结果）

先确保环境起来：

```bash
docker compose up -d pgvector ollama
docker exec -it locke-ollama ollama pull bge-m3     # 约 1.2GB
./mvnw spring-boot:run                              # 默认 dev profile
```

### 实验 1：亲眼看看嵌入模型吐出来的东西

```bash
curl http://localhost:11434/api/embed -d '{"model":"bge-m3","input":"火系精灵"}'
```

你会看到 `embeddings` 数组。**数一下长度，应该是 1024。** 再换几句意思相近/相差很远的
文本各跑一次，观察数值变化——这就是「语义变成坐标」的过程。

### 实验 2：看表结构和索引

```bash
docker exec -it locke-pgvector psql -U postgres -d locke -c "\d vector_store"
docker exec -it locke-pgvector psql -U postgres -d locke -c "\di"
```

确认 `embedding` 列是 `vector(1024)`、索引是 `hnsw ... vector_cosine_ops`。

### 实验 3：自己写一条向量检索 SQL

先从库里捞一条已有的向量，和它自己比一次（距离应该是 0）：

```sql
-- 看有多少条向量
SELECT count(*) FROM vector_store;

-- 按来源统计，确认入库覆盖了哪些语料
SELECT metadata->>'docType' AS 类型, count(*)
FROM vector_store GROUP BY 1 ORDER BY 2 DESC;

-- 拿某一条自己的向量去检索，第一条必然是它自己（距离 0）
SELECT id, metadata->>'spiritName' AS 精灵, content <=> embedding AS 到自己,
       content <=> (SELECT embedding FROM vector_store LIMIT 1) AS 到第一条
FROM vector_store
ORDER BY 到第一条
LIMIT 5;
```

这个实验能让你直接看到「距离越小越相似」这件事，以及 `<=>` 操作符怎么用。

### 实验 4：调 topK 和阈值，观察结果变化

```bash
# 先看当前会召回什么
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"question":"深渊罗隐怎么进化？"}' | jq '.sources | length, .[0].score'

# 只召回 1 条
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"question":"深渊罗隐怎么进化？","topK":1}' | jq '.sources | length'

# 把阈值提到 0.9，看是不是什么都召不回了（这就是阈值的作用）
```

`ChatResponse` 里的 `sources[].score` 就是每条命中文档的相似度，
**这是你调参时唯一的客观依据**。

### 实验 5：故意把维度改错，看报错长什么样

把 `.env` 里改成 `EMBEDDING_DIMENSIONS=768`（别的都不动），重启应用再检索一次。
你会看到明确的维度不匹配错误。**亲手制造一次这个错误，以后遇到了能立刻认出来。**

### 实验 6：验证幂等

```bash
curl -X POST localhost:8080/api/ingest -H 'Content-Type: application/json' -d '{"source":"pets"}'
# 记下条数
docker exec -it locke-pgvector psql -U postgres -d locke -c "SELECT count(*) FROM vector_store;"
# 再跑一遍同样的入库
curl -X POST localhost:8080/api/ingest -H 'Content-Type: application/json' -d '{"source":"pets"}'
# 条数应该完全一样 —— 这就是确定性 id 的价值
```

---

## 8. 踩坑清单

| # | 坑 | 症状 | 正确做法 |
|---|---|---|---|
| 1 | **维度不一致** | 写库报 `expected 1024 dimensions, not 768` | `EMBEDDING_DIMENSIONS`、`pgvector.dimensions`、SQL 的 `vector(N)` 三处同步改 |
| 2 | **索引 ops 与查询操作符不匹配** | 不报错，只是**慢**（全表扫） | 建索引用 `vector_cosine_ops`，查询就必须用 `<=>`；距离类型由 `distance-type` 决定 |
| 3 | **阈值语义搞反** | 阈值调大反而召回更多？ | pgvector `<=>` 是距离（越小越近）；Spring AI 的 `similarityThreshold` 是相似度（越大越相似） |
| 4 | **忘了 `CREATE EXTENSION vector`** | 建表报 `type "vector" does not exist` | 迁移脚本第一行就写 `CREATE EXTENSION IF NOT EXISTS vector;` |
| 5 | **`metadata` 用 `json` 建不了 GIN 索引** | 想加索引报错 | 需要按元数据过滤时改成 `jsonb`（Spring AI 写入时带 `?::jsonb` 强转，两种类型都能写） |
| 6 | **换了嵌入模型不重灌** | 不报错，但答案明显变差 | 向量空间不能混用，**换模型必须清表重灌** |
| 7 | **HNSW 超 2000 维** | 建索引或查询报错 | 降维，或改 `index-type: IVFFLAT` |
| 8 | **长文本被静默截断** | 不报错，长文档检索不到 | `spring.ai.ollama.embedding.num-ctx` 默认只有 2048，本项目已调到 8192 |
| 9 | **重复入库产生重复向量** | 条数不断增长 | 用确定性 id + `ON CONFLICT DO UPDATE`（本项目已做） |
| 10 | **两套建表逻辑打架** | 表结构和你预期的不一致 | `initialize-schema: false`，DDL 只由 Flyway 管 |

---

## 9. 自测题

能答出来说明你真的学会了（答案都在本文档里）：

1. 为什么「那个地系的终形态」这句话用 `LIKE` 搜不到，向量检索却能找到？
2. `vector(1024)` 里的 1024 如果和你用的嵌入模型输出维度对不上，会在**哪个时刻**、
   报**什么错**？
3. pgvector 的 `<=>` 返回 0.2，说明两条文本相似度大约是多少？
   Spring AI 里 `similarityThreshold(0.8)` 会过滤掉它吗？
4. 为什么本项目把 `initialize-schema` 设成 `false`？如果设成 `true` 会发生什么？
5. 如果不用确定性 id、让 Spring AI 自己生成，可能出现哪两种坏结果？
6. `VectorStore.add()` 内部帮你做了哪两件事（一件是网络调用，一件是数据库操作）？
7. 为什么 `RetrieverService` 在向量检索无命中时要回退到 SQL 查询？举一个向量检索
   必然表现不好的例子。
8. 你换成另一个 1024 维的嵌入模型，SQL 要不要改？已经入库的数据要不要重灌？为什么？

---

## 10. 延伸阅读

- pgvector 官方 README（操作符、索引类型、性能调优最权威）：
  https://github.com/pgvector/pgvector
- Spring AI 的 VectorStore API 与 pgvector 配置项：
  https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- Spring AI 的 ETL Pipeline（reader → splitter → writer 这条链的抽象）：
  https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
- bge-m3 论文（理解为什么它同时支持稠密/稀疏/多向量检索）：
  https://arxiv.org/abs/2402.03216
