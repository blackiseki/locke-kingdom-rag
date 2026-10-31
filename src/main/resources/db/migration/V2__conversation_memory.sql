-- ============================================================
-- 会话记忆：多轮上下文 + 滚动摘要压缩
--
-- 设计要点（改表之前请先看完）：
--
-- 1) 消息主键用 bigserial，不用「session_id + seq」。
--    用 seq 就得在会话行上维护一个自增计数器，但一问一答要插两条消息，
--    一旦某次插入失败，seq 就会留下空洞；用自增 id 时顺序天然单调，
--    压缩水位线（summary_upto_id）也只需要记一个 bigint。
--
-- 2) 压缩水位线放在会话行上，和摘要放在一起。
--    摘要和「摘要覆盖到哪条消息」必须原子地一起更新，
--    分成两张表或两个字段各自更新会出现「摘要已换、水位没动」的错位，
--    表现为同一段历史被重复折叠进摘要，越压越乱。
--
-- 3) summary 是<b>滚动</b>的：每次压缩把「上一版摘要 + 新折叠的消息」
--    重新压成一版，而不是追加拼接，否则摘要本身会无限膨胀。
--
-- 4) 不存上一轮的 sources（引用出处）。
--    历史里的 [1] [2] 只对当时的参考资料有效，存回来也没法跨轮复用；
--    真正需要出处时应当用 insight/日志，而不是让模型把旧编号当新编号引。
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_session (
    id                 varchar(64) PRIMARY KEY,
    title              varchar(200),
    -- 压缩后的历史摘要（滚动覆盖，可为空表示还没压缩过）
    summary            text,
    -- 已折叠进 summary 的最大消息 id；id <= 该值的消息不再进入 prompt
    summary_upto_id    bigint      NOT NULL DEFAULT 0,
    -- 压缩次数，用于观测「这段会话被压过几次」
    compression_count  integer     NOT NULL DEFAULT 0,
    summary_updated_at timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_message (
    id         bigserial PRIMARY KEY,
    session_id varchar(64) NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    role       varchar(16) NOT NULL,          -- user | assistant
    content    text        NOT NULL,
    -- 写入时算好的 token 估算值；读历史时按预算裁剪要用，
    -- 每次都重算一遍纯属浪费 CPU（一条消息可能被读很多轮）
    tokens     integer     NOT NULL DEFAULT 0,
    provider   varchar(40),
    model      varchar(80),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 按会话倒序取最近 N 条（findMessagesAfter 的主查询）
CREATE INDEX IF NOT EXISTS idx_chat_message_session_id
    ON chat_message (session_id, id);

-- 会话列表按最近活跃排序
CREATE INDEX IF NOT EXISTS idx_chat_session_updated_at
    ON chat_session (updated_at DESC);
