package com.locke.ai.ingestion.reader;

import com.locke.ai.domain.dto.TypeRecord;
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
 * 读取几张「参考数据」小表，产出向量文档：
 * <ul>
 *   <li>{@code types.json}（19 条属性克制）——同时供 {@code element_type} 表使用</li>
 *   <li>{@code game_terms.json}（53 条术语，中文解释很完整）</li>
 *   <li>{@code personalities.json}（30 条性格）</li>
 *   <li>{@code magic_items.json}（5 条血脉魔法）</li>
 * </ul>
 *
 * <p>这几张表加起来只有 40KB，但覆盖了「印记是什么」「胆小性格加什么」
 * 这类高频机制问题，性价比最高。
 *
 * <p>除了 {@code types.json} 用强类型（它要写进 {@code element_type} 表），
 * 其余按 {@code Map} 处理：字段少、改动频繁，为它们写 record 不划算。
 *
 * <p><b>缺文件只记 warning 不报错</b>：这几张表都不是回答问题的必要条件，
 * 少了应该降级而不是让整次入库失败。
 */
@Component
public class ReferenceDataReader {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataReader.class);

    private final DataRoot dataRoot;

    public ReferenceDataReader(DataRoot dataRoot) {
        this.dataRoot = dataRoot;
    }

    public record ReadResult(List<TypeRecord> elementTypes, List<Document> documents, List<String> warnings) {
        public ReadResult {
            elementTypes = List.copyOf(elementTypes);
            documents = List.copyOf(documents);
            warnings = List.copyOf(warnings);
        }
    }

    public ReadResult readAll() {
        List<Document> documents = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<TypeRecord> types = readTypes(warnings);
        documents.addAll(typeDocuments(types));
        documents.addAll(termDocuments(warnings));
        documents.addAll(personalityDocuments(warnings));
        documents.addAll(magicItemDocuments(warnings));

        log.info("参考数据读取完成：属性 {} 条，文档合计 {} 篇", types.size(), documents.size());
        return new ReadResult(types, documents, warnings);
    }

    // ============================================================
    // types.json —— 属性克制
    // ============================================================

    private List<TypeRecord> readTypes(List<String> warnings) {
        Path file = dataRoot.file("types.json");
        if (!Files.isRegularFile(file)) {
            warnings.add("缺少 types.json，属性克制表将为空");
            return List.of();
        }
        try {
            List<TypeRecord> types = JsonUtils.fromJsonList(Files.readString(file, StandardCharsets.UTF_8), TypeRecord.class);
            if (types.isEmpty()) {
                warnings.add("types.json 解析出 0 条记录");
            }
            return types;
        } catch (IOException | RuntimeException e) {
            warnings.add("types.json 解析失败: " + e.getMessage());
            return List.of();
        }
    }

    private List<Document> typeDocuments(List<TypeRecord> types) {
        List<Document> documents = new ArrayList<>(types.size());
        String source = dataRoot.sourceId("types.json");
        for (TypeRecord type : types) {
            StringBuilder sb = new StringBuilder("属性：").append(type.label()).append('（').append(type.name()).append("）\n");
            List<String> vulnerable = TypeRecord.toLabels(type.vulnerableTo());
            List<String> resistant = TypeRecord.toLabels(type.resistantTo());
            sb.append("弱点：").append(joinOrNone(vulnerable)).append('\n');
            sb.append("抵抗：").append(joinOrNone(resistant)).append('\n');
            // types.json 只给了「防御面」（我弱什么、我抗什么），
            // 但用户最常问的恰恰是「X 克制什么」——那是同一个关系的反向读法：
            // 某属性 U 的 vulnerable_to 里含有 T，就等于「T 克制 U」。
            // 不把反向关系补出来，模型就只能答防御面，然后如实说「资料里没有」
            //（这是我在真实提问时发现的缺口，问「火系克制哪些属性」直接答不出来）。
            sb.append("克制（攻击这些属性时伤害更高）：").append(joinOrNone(strongAgainst(types, type))).append('\n');
            sb.append("被抵抗（攻击这些属性时伤害更低）：").append(joinOrNone(resistedBy(types, type)));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", source);
            metadata.put("docType", "element-type");
            metadata.put("elementType", type.label());
            metadata.put("elementTypeId", type.id());
            documents.add(Document.builder().text(sb.toString()).metadata(metadata).build());
        }
        return documents;
    }

    /** 反向查出「这个属性克制谁」：谁的 vulnerable_to 里含有我，我就克制谁。 */
    private static List<String> strongAgainst(List<TypeRecord> all, TypeRecord self) {
        return all.stream()
                .filter(other -> other.vulnerableTo().contains(self.name()))
                .map(TypeRecord::label)
                .toList();
    }

    /** 反向查出「谁抵抗我」：谁的 resistant_to 里含有我。 */
    private static List<String> resistedBy(List<TypeRecord> all, TypeRecord self) {
        return all.stream()
                .filter(other -> other.resistantTo().contains(self.name()))
                .map(TypeRecord::label)
                .toList();
    }

    private static String joinOrNone(List<String> labels) {
        return (labels == null || labels.isEmpty()) ? "无" : String.join("、", labels);
    }
    // ============================================================
    // game_terms.json —— 术语
    // ============================================================

    private List<Document> termDocuments(List<String> warnings) {
        List<Map<String, Object>> terms = readMapList("game_terms.json", warnings);
        List<Document> documents = new ArrayList<>(terms.size());
        String source = dataRoot.sourceId("game_terms.json");
        for (Map<String, Object> term : terms) {
            Map<String, Object> localized = asMap(term.get("localized"));
            String name = com.locke.ai.util.LocalizedText.name(localized, str(term.get("key")));
            String description = com.locke.ai.util.LocalizedText.description(localized, str(term.get("description")));
            if (name == null || description == null) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", source);
            metadata.put("docType", "term");
            metadata.put("termName", name);
            documents.add(Document.builder()
                    .text("术语：" + name + "\n说明：" + description)
                    .metadata(metadata)
                    .build());
        }
        return documents;
    }

    // ============================================================
    // personalities.json —— 性格
    // ============================================================

    /** 性格的六项修正字段 -> 中文说明。字段名以 {@code _mod_pct} 结尾，按百分比渲染。 */
    private static final Map<String, String> PERSONALITY_MODS = new LinkedHashMap<>(Map.of(
            "hp_mod_pct", "HP",
            "phy_atk_mod_pct", "物攻",
            "mag_atk_mod_pct", "魔攻",
            "phy_def_mod_pct", "物防",
            "mag_def_pct", "魔防",
            "spd_mod_pct", "速度"));

    private List<Document> personalityDocuments(List<String> warnings) {
        List<Map<String, Object>> rows = readMapList("personalities.json", warnings);
        List<Document> documents = new ArrayList<>(rows.size());
        String source = dataRoot.sourceId("personalities.json");
        for (Map<String, Object> row : rows) {
            // 性格的 localized.zh 是字符串形式，例如 {"zh": "胆小"}
            String name = com.locke.ai.util.LocalizedText.name(asMap(row.get("localized")), str(row.get("name")));
            if (name == null) {
                continue;
            }
            List<String> mods = new ArrayList<>();
            for (Map.Entry<String, String> entry : PERSONALITY_MODS.entrySet()) {
                Object raw = row.get(entry.getKey());
                if (raw instanceof Number number && number.doubleValue() != 0d) {
                    double value = number.doubleValue();
                    mods.add(entry.getValue() + (value > 0 ? " +" : " ") + trimNumber(value) + "%");
                }
            }
            StringBuilder sb = new StringBuilder("性格：").append(name);
            if (!mods.isEmpty()) {
                sb.append("\n能力修正：").append(String.join("、", mods));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", source);
            metadata.put("docType", "personality");
            metadata.put("personalityName", name);
            documents.add(Document.builder().text(sb.toString()).metadata(metadata).build());
        }
        return documents;
    }

    // ============================================================
    // magic_items.json —— 血脉魔法
    // ============================================================

    private List<Document> magicItemDocuments(List<String> warnings) {
        List<Map<String, Object>> rows = readMapList("magic_items.json", warnings);
        List<Document> documents = new ArrayList<>(rows.size());
        String source = dataRoot.sourceId("magic_items.json");
        for (Map<String, Object> row : rows) {
            Map<String, Object> localized = asMap(row.get("localized"));
            String name = com.locke.ai.util.LocalizedText.name(localized, str(row.get("name")));
            if (name == null) {
                continue;
            }
            String description = com.locke.ai.util.LocalizedText.description(localized, str(row.get("description")));
            StringBuilder sb = new StringBuilder("血脉魔法：").append(name);
            if (description != null) {
                sb.append("\n说明：").append(description);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", source);
            metadata.put("docType", "magic-item");
            metadata.put("magicItemName", name);
            documents.add(Document.builder().text(sb.toString()).metadata(metadata).build());
        }
        return documents;
    }

    // ============================================================
    // 公共
    // ============================================================

    private List<Map<String, Object>> readMapList(String fileName, List<String> warnings) {
        Path file = dataRoot.file(fileName);
        if (!Files.isRegularFile(file)) {
            warnings.add("缺少 " + fileName + "，相关语料将被跳过");
            return List.of();
        }
        try {
            return JsonUtils.fromJsonMapList(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            warnings.add(fileName + " 解析失败: " + e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
