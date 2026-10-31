package com.locke.ai.ingestion.reader;

import com.locke.ai.exception.IngestException;
import com.locke.ai.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取 {@code items.json}（道具，4540 条）。
 *
 * <p>这份数据<b>没有 {@code localized} 包装</b>，中文直接写在
 * {@code name} / {@code description} / {@code flavor_text} 里，
 * 所以不需要走 {@code LocalizedText}。
 *
 * <p>字段结构差异较大（{@code acquire_ways} 是字符串数组、{@code recipes} 是嵌套对象），
 * 用一个 record 去 bind 不划算，这里按 {@code Map} 接住：
 * 只要中文名和描述在，就够 RAG 用了。
 *
 * <p>4540 条道具的描述文本合计约 15 万字，是本次最便宜的一批语料。
 */
@Component
public class ItemDataReader {

    private static final Logger log = LoggerFactory.getLogger(ItemDataReader.class);

    /** 单条道具文档的文本上限，防止个别超长描述把 prompt 撑爆。 */
    private static final int MAX_ITEM_TEXT = 800;

    private final DataRoot dataRoot;

    public ItemDataReader(DataRoot dataRoot) {
        this.dataRoot = dataRoot;
    }

    public record ReadResult(List<Document> documents, List<String> warnings) {
        public ReadResult {
            documents = List.copyOf(documents);
            warnings = List.copyOf(warnings);
        }
    }

    public ReadResult readAll() {
        Path file = dataRoot.file("items.json");
        if (!Files.isRegularFile(file)) {
            throw new IngestException("道具表不存在: " + file);
        }

        List<Map<String, Object>> items;
        try {
            items = JsonUtils.fromJsonMapList(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IngestException("读取道具表失败: " + file, e, "items.json");
        }

        List<Document> documents = new ArrayList<>(items.size());
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String name = str(item.get("name"));
            if (name == null) {
                continue;
            }
            String id = str(item.get("id"));
            String text = render(item, name);
            if (text == null) {
                warnings.add("道具 " + name + " 没有可用文本，已跳过");
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", dataRoot.sourceId("items.json"));
            metadata.put("docType", "item");
            metadata.put("itemId", id);
            metadata.put("itemName", name);
            putIfPresent(metadata, "category", str(item.get("category")));

            documents.add(Document.builder().text(text).metadata(metadata).build());
        }

        log.info("道具语料读取完成：{}/{} 条", documents.size(), items.size());
        return new ReadResult(documents, warnings);
    }

    private static String render(Map<String, Object> item, String name) {
        StringBuilder sb = new StringBuilder("道具：").append(name).append('\n');
        String category = str(item.get("category"));
        String typeDesc = str(item.get("type_desc"));
        if (category != null || typeDesc != null) {
            sb.append("分类：").append(category == null ? typeDesc : category);
            if (typeDesc != null && !typeDesc.equals(category)) {
                sb.append("（").append(typeDesc).append('）');
            }
            sb.append('\n');
        }
        String quality = str(item.get("quality_label"));
        if (quality != null) {
            sb.append("品质：").append(quality).append('\n');
        }
        String description = str(item.get("description"));
        if (description != null) {
            sb.append("描述：").append(description).append('\n');
        }
        String flavor = str(item.get("flavor_text"));
        if (flavor != null) {
            sb.append("背景：").append(flavor).append('\n');
        }
        String acquireWays = joinList(item.get("acquire_ways"));
        if (acquireWays != null) {
            sb.append("获取方式：").append(acquireWays).append('\n');
        }

        String text = sb.toString().trim();
        // 只有名字、没有任何描述的道具，入向量库只会产生噪声
        if (text.length() <= "道具：".length() + name.length() + 1) {
            return null;
        }
        return text.length() <= MAX_ITEM_TEXT ? text : text.substring(0, MAX_ITEM_TEXT) + "…";
    }

    private static String joinList(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            List<String> parts = list.stream().map(ItemDataReader::str).filter(java.util.Objects::nonNull).toList();
            return parts.isEmpty() ? null : String.join("、", parts);
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
