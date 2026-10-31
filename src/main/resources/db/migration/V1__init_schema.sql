-- ============================================================
-- 洛克王国 AI 助手 —— 初始化 schema
--
-- 语料来源：rocom.aoe.top 数据站导出的游戏数据（目录由 LOCKE_DATA_ROOT 指定）
--
-- 重要：本文件是唯一的 DDL 来源，因此 application.yml 中必须设置
--       spring.ai.vectorstore.pgvector.initialize-schema: false
--       否则 Spring AI 会再建一次 vector_store 表，两边定义容易打架。
--
-- embedding 维度必须与向量模型一致：本地 Ollama bge-m3 = 1024。
-- 换模型（如 mxbai-embed-large=1024、nomic-embed-text=768）要同时改
--       vector(1024)、spring.ai.vectorstore.pgvector.dimensions
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;
-- 按精灵名模糊搜索
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================
-- 向量表：遵循 Spring AI PgVectorStore 的约定结构
-- ============================================================
CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    embedding vector(1024)
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- 注意：metadata 是 json 而非 jsonb，json 上没有可用的 GIN 操作符类，
-- 所以不加 metadata 索引。要按 metadata 过滤就把它改成 jsonb
--（Spring AI 写入时带 ?::jsonb 强转，两种类型都能用）。

-- ============================================================
-- 属性克制表：来自 types.json（19 项）
-- ============================================================
CREATE TABLE IF NOT EXISTS element_type (
    id                integer PRIMARY KEY,
    name              varchar(32) NOT NULL,     -- 英文名，如 Ground
    label             varchar(32) NOT NULL,     -- 中文名，如 地
    vulnerable_to     text[]      NOT NULL DEFAULT '{}',   -- 弱点（英文名）
    resistant_to      text[]      NOT NULL DEFAULT '{}',   -- 抵抗（英文名）
    vulnerable_labels text[]      NOT NULL DEFAULT '{}',   -- 弱点的中文名
    resistant_labels  text[]      NOT NULL DEFAULT '{}'    -- 抵抗的中文名
);

-- ============================================================
-- 精灵：来自 pets/{id}.json
-- ============================================================
CREATE TABLE IF NOT EXISTS spirit (
    -- id 直接用数据里的 PETBASE 主键，不用自增：
    -- 正典精灵在 3000~3999，其余是首领/异形态，id 本身有含义
    id                     bigint PRIMARY KEY,
    species_id             integer,              -- 图鉴/手册 id，用于把同一进化家族归组
    name                   varchar(64),          -- 英文/拼音名
    display_name           varchar(64) NOT NULL, -- 中文名
    form                   varchar(64),
    main_type              varchar(32),          -- ElementType 枚举名
    sub_type               varchar(32),          -- 可空，实测 53% 的精灵没有副属性
    legacy_type            varchar(32),          -- 血脉属性
    trait_name             varchar(64),          -- 特性名，如 圣光
    trait_effect           text,
    evolved_from_id        bigint,               -- 上一个形态，可空（595/1065 为空）
    evolve_level           integer,              -- 从进化条件中文里解析出的等级，可能为空

    base_hp                integer,
    base_phy_atk           integer,
    base_mag_atk           integer,
    base_phy_def           integer,
    base_mag_def           integer,
    base_spd               integer,
    base_stat_total        integer,              -- 六项之和，方便粗比较

    implemented            boolean NOT NULL DEFAULT false,
    is_leader_form         boolean NOT NULL DEFAULT false,
    preferred_attack_style varchar(32),          -- Both / Physical / Magic

    classis_name           varchar(64),          -- 精灵分类，如 萌萌类精灵
    introduction           text,                 -- 图鉴介绍
    habitat                text,                 -- description_habitat
    type_desc              text,
    refresh_locations      text,                 -- 刷新地点，多条用换行拼接

    -- 捕捉参数：数据源没说明这三个数的确切含义，只入库不进向量档案
    catch_threshold        integer,
    catch_guarant_rate     integer,
    catch_ball_level       integer,

    raw_json               jsonb,                -- 原始记录存档，便于回溯字段
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_spirit_display_name_trgm
    ON spirit USING gin (display_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_spirit_main_type ON spirit (main_type);
CREATE INDEX IF NOT EXISTS idx_spirit_species_id ON spirit (species_id);
CREATE INDEX IF NOT EXISTS idx_spirit_implemented ON spirit (implemented);

-- ============================================================
-- 技能：来自 moves.json（技能总表）与精灵身上内嵌的技能
-- ============================================================
-- 注意存在两套互不相交的 id 空间：
--   moves.json 的 id 是 1~3643；精灵内嵌技能（move_pool/技能石/血脉技能）是 7 位数。
-- 两者都塞进同一张表，用 source 区分来源。
CREATE TABLE IF NOT EXISTS skill (
    id            bigint PRIMARY KEY,
    name          varchar(64),          -- 英文名
    display_name  varchar(64) NOT NULL, -- 中文名
    move_type     varchar(32),          -- 可空（moves.json 里有 2 条为 null）
    move_category varchar(32),          -- Physical Attack / Magic Attack / Status
    energy_cost   integer,
    power         integer,              -- 可空，变化类技能没有威力
    effect        text,                 -- 中文效果描述
    source        varchar(16) NOT NULL DEFAULT 'catalog'  -- catalog | pet
);

CREATE INDEX IF NOT EXISTS idx_skill_display_name_trgm
    ON skill USING gin (display_name gin_trgm_ops);

-- ============================================================
-- 精灵-技能关联
-- ============================================================
CREATE TABLE IF NOT EXISTS spirit_skill (
    spirit_id bigint  NOT NULL REFERENCES spirit(id) ON DELETE CASCADE,
    skill_id  bigint  NOT NULL REFERENCES skill(id)  ON DELETE CASCADE,
    source    varchar(16) NOT NULL,   -- level_up（升级技能）| stone（技能石）| legacy（血脉技能）
    PRIMARY KEY (spirit_id, skill_id, source)
);

CREATE INDEX IF NOT EXISTS idx_spirit_skill_skill ON spirit_skill (skill_id);
