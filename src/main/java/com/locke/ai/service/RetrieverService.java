package com.locke.ai.service;

import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.domain.enums.ElementType;
import com.locke.ai.ingestion.transformer.DocumentEnricher;
import com.locke.ai.repository.SpiritRepository;
import com.locke.ai.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索封装：向量优先，关键词兜底。
 *
 * <h3>为什么要兜底</h3>
 * 纯向量检索对「火花」这类<b>专有名词 + 极短问题</b>召回很不稳：
 * 两个字包含的语义信息太少，很容易被同属性的其它精灵挤掉。
 * 而这类问题恰恰是结构化数据能精确回答的，所以向量没命中时
 * 直接回退到 {@link SpiritRepository} 按名字/属性查。
 *
 * <p>兜底查询是本地 SQL，没有 API 成本，属于「先便宜后贵」的策略。
 *
 * <h3>兜底文档的信息量比向量档案少</h3>
 * 向量档案里有完整技能表、进化链、图鉴介绍；兜底文档只有名字、属性、
 * 种族值、进化等级这些从表里读得回来的字段。
 * 想让它也带上技能，需要额外查 {@code spirit_skill}——
 * 目前没做，因为正常入库后向量库里本来就有完整档案，兜底只是保底路径。
 */
@Service
public class RetrieverService {

    private static final Logger log = LoggerFactory.getLogger(RetrieverService.class);

    private final VectorRepository vectorRepository;
    private final SpiritRepository spiritRepository;

    public RetrieverService(VectorRepository vectorRepository, SpiritRepository spiritRepository) {
        this.vectorRepository = vectorRepository;
        this.spiritRepository = spiritRepository;
    }

    /**
     * 检索相关资料。
     *
     * @param question  用户问题
     * @param topK      召回条数
     * @param threshold 相似度阈值
     * @return 命中的文档，可能为空（空表示确实没有资料，调用方要如实告诉用户）
     */
    public List<Document> retrieve(String question, int topK, double threshold) {
        List<Document> hits = vectorRepository.search(question, topK, threshold);
        if (!hits.isEmpty()) {
            log.debug("向量检索命中 {} 条: {}", hits.size(), summariseSources(hits));
            return hits;
        }

        log.debug("向量检索无命中，回退到结构化查询: question={}", question);
        List<Document> fallback = keywordFallback(question, topK);
        if (!fallback.isEmpty()) {
            log.debug("结构化兜底命中 {} 条", fallback.size());
        }
        return fallback;
    }

    /** 带元数据过滤的检索，例如只在数据字典里找「战斗数值配置在哪张表」。 */
    public List<Document> retrieve(String question, int topK, double threshold, String filterExpression) {
        return vectorRepository.search(question, topK, threshold, filterExpression);
    }

    /**
     * 关键词兜底：先按精灵名模糊匹配，再退一步按属性词匹配。
     *
     * <p>顺序有讲究——「火系精灵有哪些」该走属性查询，「火花」该走名字查询，
     * 两个都试一遍比猜一个更省事。
     */
    private List<Document> keywordFallback(String question, int topK) {
        List<Spirit> spirits = spiritRepository.searchByDisplayName(question, topK);
        if (spirits.isEmpty()) {
            ElementType element = ElementType.fromJson(question);
            if (element != ElementType.UNKNOWN) {
                spirits = spiritRepository.findByElement(element, topK);
            }
        }
        return spirits.stream().map(RetrieverService::toDocument).toList();
    }

    /** 把结构化记录包装成 Document，让上层不用区分「这条是向量来的还是 SQL 来的」。 */
    private static Document toDocument(Spirit spirit) {
        StringBuilder sb = new StringBuilder("精灵：").append(spirit.displayName()).append('\n');
        sb.append("属性：").append(spirit.mainTypeLabel());
        if (spirit.subType() != null) {
            sb.append(" / ").append(spirit.subType().label());
        }
        sb.append('\n');
        Spirit.WorldProfile profile = spirit.worldProfile();
        if (profile != null && profile.classisName() != null && !profile.classisName().isBlank()) {
            sb.append("分类：").append(profile.classisName().trim()).append('\n');
        }
        Integer evolveLevel = spirit.evolveLevel();
        if (evolveLevel != null) {
            sb.append("进化等级：").append(evolveLevel).append('\n');
        }
        sb.append("种族值合计：").append(spirit.baseStatTotal());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentEnricher.META_SOURCE, "spirit-table");
        metadata.put(DocumentEnricher.META_DOC_TYPE, DocumentEnricher.DOC_TYPE_SPIRIT);
        metadata.put(DocumentEnricher.META_SPIRIT_ID, spirit.id());
        metadata.put(DocumentEnricher.META_SPIRIT_NAME, spirit.displayName());
        metadata.put(DocumentEnricher.META_ELEMENT_TYPE, spirit.mainTypeLabel());

        return Document.builder().text(sb.toString()).metadata(metadata).build();
    }

    private static String summariseSources(List<Document> documents) {
        return documents.stream()
                .map(document -> String.valueOf(document.getMetadata().get(DocumentEnricher.META_SOURCE)))
                .distinct()
                .toList()
                .toString();
    }
}
