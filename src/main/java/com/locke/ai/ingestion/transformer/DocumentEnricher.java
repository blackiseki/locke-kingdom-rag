package com.locke.ai.ingestion.transformer;

import com.locke.ai.domain.entity.Spirit;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 给文档补元数据并写入确定性 id，是 ETL 的<b>最后一步</b>。
 *
 * <p>元数据有两个作用，缺一不可：
 * <ol>
 *   <li><b>可解释</b>：回答要能告诉用户「这条来自哪个文件的哪只精灵」，
 *       模型说错话时才有得追溯；</li>
 *   <li><b>可过滤</b>：{@code elementType == '火'} 这类过滤靠元数据字段，
 *       而不是把条件写进自然语言 prompt。</li>
 * </ol>
 * 元数据 key 统一在这里定义，别在别处写字符串字面量——拼错一个字母不会编译报错，
 * 只会静默地让过滤条件失效。
 *
 * <h3>为什么 id 一定要在这里生成</h3>
 * Spring AI 的 {@code TextSplitter} 切分时会自己造 id，行为不保证：
 * 同一个文档切出的多块可能共用同一个 id——那样 PgVectorStore 的
 * {@code ON CONFLICT (id) DO UPDATE} 会把多块<b>合并成一行，静默丢数据</b>；
 * 也可能在 id 后拼后缀，而 {@code vector_store.id} 是 uuid 类型，
 * 拼了后缀就直接写库报错。把 id 的生成收到最后一步、由自己掌控，这两种坑都不会踩。
 *
 * <h3>幂等性</h3>
 * id 由「来源 + 块序号」决定，所以可以反复跑同一份语料，行数不会膨胀。
 * 已知缺口：如果某个文件改短了，多出来的旧块不会被自动清掉
 *（它占着 id，但不在本轮写入集合里）。补法是入库前按 source 删除，
 * 见 {@code VectorRepository} 的注释。
 */
@Component
public class DocumentEnricher {

    public static final String META_SOURCE = "source";
    public static final String META_DOC_TYPE = "docType";
    public static final String META_CHUNK_INDEX = "chunkIndex";
    public static final String META_SPIRIT_NAME = "spiritName";
    public static final String META_SPIRIT_ID = "spiritId";
    public static final String META_SPECIES_ID = "speciesId";
    public static final String META_ELEMENT_TYPE = "elementType";

    /** 文档类型取值。 */
    public static final String DOC_TYPE_SPIRIT = "spirit";
    public static final String DOC_TYPE_SKILL = "skill";
    public static final String DOC_TYPE_ITEM = "item";
    public static final String DOC_TYPE_TERM = "term";
    public static final String DOC_TYPE_PERSONALITY = "personality";
    public static final String DOC_TYPE_MAGIC_ITEM = "magic-item";
    public static final String DOC_TYPE_ELEMENT_TYPE = "element-type";
    public static final String DOC_TYPE_DATA_DICTIONARY = "data-dictionary";

    /** 精灵档案：额外补上结构化字段，方便按属性过滤。 */
    public List<Document> enrichWithSpirit(List<Document> documents, Spirit spirit, String source) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> enriched = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            // putIfAbsent：reader/splitter 更清楚来源，别把它们已经写好的值覆盖掉
            metadata.putIfAbsent(META_SOURCE, source);
            metadata.putIfAbsent(META_DOC_TYPE, DOC_TYPE_SPIRIT);
            metadata.put(META_CHUNK_INDEX, index);
            if (spirit != null) {
                putIfPresent(metadata, META_SPIRIT_NAME, spirit.displayName());
                metadata.put(META_SPIRIT_ID, spirit.id());
                metadata.put(META_SPECIES_ID, spirit.groupId());
                if (spirit.mainType() != null) {
                    metadata.put(META_ELEMENT_TYPE, spirit.mainTypeLabel());
                }
                if (spirit.subType() != null) {
                    metadata.put("subElementType", spirit.subType().label());
                }
            }
            enriched.add(rebuild(document, metadata, source, index));
        }
        return enriched;
    }

    /** 通用：给已经带好文本的文档补 source / docType 和确定性 id。 */
    public List<Document> enrich(List<Document> documents, String source, String docType) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> enriched = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.putIfAbsent(META_SOURCE, source);
            metadata.putIfAbsent(META_DOC_TYPE, docType);
            metadata.put(META_CHUNK_INDEX, index);
            enriched.add(rebuild(document, metadata, source, index));
        }
        return enriched;
    }

    private static Document rebuild(Document document, Map<String, Object> metadata, String source, int index) {
        return Document.builder()
                // 显式给 id：不给的话每次入库都随机生成，同一份语料重复入库会变成新增
                .id(stableId(source, index))
                .text(document.getText())
                .metadata(metadata)
                .build();
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    /**
     * 由「来源 + 块序号」推出稳定 UUID（name-based，同输入必同输出）。
     *
     * <p>用块序号而不是正文哈希：正文改一个字就换 id，会留下删不掉的旧行；
     * 用序号则新内容覆盖旧内容，行数不膨胀。
     */
    public static String stableId(String source, int index) {
        String seed = (source == null ? "unknown" : source) + "#" + index;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
