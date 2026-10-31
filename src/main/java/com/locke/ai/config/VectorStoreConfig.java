package com.locke.ai.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库与 ETL 相关配置。
 *
 * <h3>PgVectorStore 本身不在这里 new</h3>
 * 它由 {@code spring-ai-starter-vector-store-pgvector} 依据
 * {@code spring.ai.vectorstore.pgvector.*} 自动装配。
 * 手写 {@code @Bean VectorStore} 会顶掉自动装配，然后你得自己负责
 * 维度校验、批量写入、filter 表达式转换这一整套，得不偿失。
 * 真要定制（换距离策略、换 FilterExpressionConverter），
 * 注入 {@code JdbcTemplate} + {@code EmbeddingModel} 自己组装
 * {@code PgVectorStore.builder(...)}，并把自动装配关掉。
 *
 * <h3>表结构归 Flyway</h3>
 * {@code spring.ai.vectorstore.pgvector.initialize-schema} 保持 {@code false}，
 * DDL 全在 {@code db/migration/V1__init_schema.sql}。
 * 注意 2.0 里这个开关默认已经是 false（老版本默认建表），属于破坏性变更。
 *
 * <h3>换向量模型时必须同步改三处</h3>
 * <ol>
 *   <li>{@code spring.ai.ollama.embedding.model}</li>
 *   <li>{@code spring.ai.vectorstore.pgvector.dimensions}</li>
 *   <li>{@code V1__init_schema.sql} 里的 {@code vector(N)}——已建表的话还要写一条迁移改列类型</li>
 * </ol>
 * 现在三处都是 bge-m3 的 1024。不一致的典型症状是写库时报
 * {@code expected 1024 dimensions, not 768}。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 长文本的 token 级切分器，用于 {@code BinData/*.md} 数据字典这类
     * 没有「单精灵」结构的文档（精灵档案不走这里，见
     * {@link com.locke.ai.ingestion.splitter.SpiritChunkSplitter}）。
     *
     * <p><b>必须用 builder</b>：Spring AI 2.0 里 {@code TokenTextSplitter} 的
     * 5 个构造器全部标记了 {@code @Deprecated(since = "2.0.0-M3", forRemoval = true)}，
     * 直接 new 会编译告警、未来直接编不过。
     */
    @Bean
    public TokenTextSplitter tokenTextSplitter(
            @Value("${locke.ai.ingest.token-chunk-size:800}") int chunkSize) {
        // TokenTextSplitter 内部会校验 chunkSize > minChunkSizeChars，
        // 所以两个「下限」参数跟着 chunkSize 缩放，而不是写死常量——
        // 否则用户把 token-chunk-size 配成 100 就会启动失败
        int effectiveChunkSize = Math.max(chunkSize, 50);
        return TokenTextSplitter.builder()
                .withChunkSize(effectiveChunkSize)  // 每块目标 token 数
                .withMinChunkSizeChars(Math.max(1, effectiveChunkSize / 4))
                .withMinChunkLengthToEmbed(Math.max(1, effectiveChunkSize / 40))
                .withMaxNumChunks(2000)             // 单文档最多切多少块，防止超长文档炸掉
                .withKeepSeparator(true)            // 保留分隔符，避免把句子切断后语义丢失
                .build();
    }
}
