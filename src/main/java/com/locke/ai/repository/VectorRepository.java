package com.locke.ai.repository;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 向量库操作封装。
 *
 * <p>把 Spring AI 的 {@link VectorStore} 包一层，目的有三个：
 * <ol>
 *   <li>业务层不直接依赖 Spring AI 的检索 API，换库（Milvus / Qdrant）时改动集中在这里；</li>
 *   <li>统一 topK / 阈值的默认值，避免每个调用点各写一套；</li>
 *   <li>提供一个 {@link #count()} 之类的运维视角方法。</li>
 * </ol>
 *
 * <p><b>2.0 的 API 变化</b>：{@code VectorStore} 只剩
 * {@code similaritySearch(SearchRequest)} 和 {@code similaritySearch(String)} 两个检索入口，
 * 老的 {@code similaritySearch(query, topK, threshold)} 重载已经被删掉了。
 * 另外注意 {@code PgVectorStore} 类注释里还写着 {@code SearchRequest.query(..).withTopK(..)}，
 * 那是过期的文档，2.0 里这些方法不存在，只能用 builder。
 */
@Repository
public class VectorRepository {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public VectorRepository(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入/覆盖向量。Document 带确定性 id 时是 upsert 语义，可以反复跑入库。 */
    public void save(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        vectorStore.add(documents);
    }

    /**
     * 相似度检索。
     *
     * @param similarityThreshold 低于该值的块直接丢弃；传 0 表示不做阈值过滤。
     *                            注意这是原始 double，不是 Double，null 会 NPE。
     */
    public List<Document> search(String query, int topK, double similarityThreshold) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        return vectorStore.similaritySearch(request);
    }

    /**
     * 带元数据过滤的检索。
     *
     * @param filterExpression 过滤表达式，例如 {@code elementType == '火' && docType == 'spirit'}
     */
    public List<Document> search(String query, int topK, double similarityThreshold, String filterExpression) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filterExpression)
                .build();
        return vectorStore.similaritySearch(request);
    }

    public void deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
    }

    /**
     * 当前向量条数。
     *
     * <p>直接查表而不用向量库 API：{@code VectorStore} 没有 count 方法
     * （各家向量库对这个操作的支持程度差太多，Spring AI 就没往接口上放）。
     */
    public int count() {
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Integer.class);
        return total == null ? 0 : total;
    }
}
