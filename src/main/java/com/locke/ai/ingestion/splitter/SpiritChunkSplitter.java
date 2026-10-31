package com.locke.ai.ingestion.splitter;

import com.locke.ai.domain.entity.Skill;
import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.ingestion.transformer.DocumentEnricher;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一只精灵渲染成「精灵档案」文档。
 *
 * <p>结构化数据（{@code pets/{id}.json}）没法直接向量化——一堆字段名和数字
 * 对语义检索毫无意义。所以这里把它渲染成一段人读得懂的 Markdown 文本，
 * 再整体作为<b>一篇文档</b>入库（约 1000~3000 字，远小于嵌入模型上限，
 * 通常不需要切块）。
 *
 * <h3>渲染时守的几条规矩</h3>
 * <ul>
 *   <li><b>不编造</b>：数据里没有的字段（比如技能学习等级——它在
 *       {@code pets/{id}.json} 里被生成脚本丢弃了）就不写，不猜一个值填上；</li>
 *   <li><b>不写含义不明的数字</b>：{@code catch_info} 那三个数只入库、不进档案，
 *       宁可答不出来也不要给一个似是而非的说明；</li>
 *   <li><b>长清单截断并注明</b>：技能超过上限时写「其余 N 个未列出」，
 *       而不是静默丢弃，免得用户以为这就是全部技能。</li>
 * </ul>
 *
 * <p>刻意没有继承 Spring AI 的 {@code TextSplitter}：继承会强制套上它自己的
 * 分块策略（并且会重算 metadata 和 id），而我们需要的是「一只精灵一篇档案」
 * 这个语义边界。要接进 Spring AI 的 ETL 管道时再改继承也不迟。
 */
@Component
public class SpiritChunkSplitter {

    /**
     * 单篇档案的字符上限。
     *
     * <p>取值要照顾 Ollama：{@code spring.ai.ollama.embedding.num-ctx} 默认只有 2048，
     * 超长文本会被静默截断。application.yml 里已经把它调到 8192，
     * 这里用 3000 字留出余量（中文大致 1 字 ≈ 1 token 量级）。
     */
    private static final int MAX_CHARS = 3000;

    /** 血脉技能 / 技能石只列名字，各自最多列这么多条。 */
    private static final int MAX_NAME_ONLY = 20;

    private final int maxMovesPerDoc;
    private final boolean includeLegacyMoves;

    public SpiritChunkSplitter(
            @Value("${locke.ai.ingest.max-moves-per-doc:25}") int maxMovesPerDoc,
            @Value("${locke.ai.ingest.include-legacy-moves:true}") boolean includeLegacyMoves) {
        this.maxMovesPerDoc = Math.max(1, maxMovesPerDoc);
        this.includeLegacyMoves = includeLegacyMoves;
    }

    /** 渲染一只精灵的档案，通常返回 1 篇；超长时按 {@link #MAX_CHARS} 切块。 */
    public List<Document> split(Spirit spirit, String source) {
        if (spirit == null || spirit.displayName() == null || spirit.displayName().isBlank()) {
            return List.of();
        }
        String text = sanitize(renderArchive(spirit));
        if (text.isBlank()) {
            return List.of();
        }

        List<String> chunks = splitText(text, MAX_CHARS, identityHeader(spirit));
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(DocumentEnricher.META_SOURCE, source);
            metadata.put(DocumentEnricher.META_DOC_TYPE, DocumentEnricher.DOC_TYPE_SPIRIT);
            metadata.put(DocumentEnricher.META_CHUNK_INDEX, index);
            metadata.put(DocumentEnricher.META_SPIRIT_ID, spirit.id());
            metadata.put(DocumentEnricher.META_SPIRIT_NAME, spirit.displayName());
            metadata.put(DocumentEnricher.META_SPECIES_ID, spirit.groupId());
            metadata.put(DocumentEnricher.META_ELEMENT_TYPE, spirit.mainTypeLabel());
            documents.add(Document.builder().text(chunks.get(index)).metadata(metadata).build());
        }
        return documents;
    }

    /** 渲染精灵档案正文。包内可见，方便单测直接断言文本内容。 */
    String renderArchive(Spirit spirit) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# ").append(spirit.displayName()).append("\n\n");

        // ---------- 基础信息 ----------
        sb.append("属性：").append(spirit.mainTypeLabel());
        if (spirit.subType() != null) {
            sb.append(" / ").append(spirit.subType().label());
        }
        sb.append('\n');
        if (spirit.defaultLegacyType() != null) {
            sb.append("血脉属性：").append(spirit.defaultLegacyType().label()).append('\n');
        }
        sb.append("图鉴编号：").append(spirit.groupId()).append('\n');
        Spirit.WorldProfile profile = spirit.worldProfile();
        if (profile != null && notBlank(profile.classisName())) {
            sb.append("分类：").append(profile.classisName().trim()).append('\n');
        }
        if (spirit.trait() != null) {
            sb.append("特性：").append(spirit.trait().label());
            String effect = spirit.trait().effect();
            if (notBlank(effect) && !effect.trim().equals(spirit.trait().label())) {
                sb.append(" —— ").append(effect.trim());
            }
            sb.append('\n');
        }
        Integer evolveLevel = spirit.evolveLevel();
        if (evolveLevel != null) {
            sb.append("进化等级：").append(evolveLevel).append('\n');
        }
        sb.append("种族值：HP ").append(nz(spirit.baseHp()))
                .append(" / 物攻 ").append(nz(spirit.basePhyAtk()))
                .append(" / 魔攻 ").append(nz(spirit.baseMagAtk()))
                .append(" / 物防 ").append(nz(spirit.basePhyDef()))
                .append(" / 魔防 ").append(nz(spirit.baseMagDef()))
                .append(" / 速度 ").append(nz(spirit.baseSpd()))
                .append("（合计 ").append(spirit.baseStatTotal()).append("）\n");

        // ---------- 进化链 ----------
        List<String> evolution = spirit.evolutionLines();
        if (evolution.size() > 1) {
            sb.append("\n## 进化链\n");
            evolution.forEach(line -> sb.append(line).append('\n'));
        }

        // ---------- 图鉴资料 ----------
        if (profile != null) {
            if (notBlank(profile.introduction())) {
                sb.append("\n## 图鉴介绍\n").append(profile.introduction().trim()).append('\n');
            }
            if (notBlank(profile.descriptionHabitat())) {
                sb.append("\n## 栖息地\n").append(profile.descriptionHabitat().trim()).append('\n');
            }
            if (notBlank(profile.typeDesc())) {
                sb.append("\n## 属性说明\n").append(profile.typeDesc().trim()).append('\n');
            }
            if (!profile.refreshLocations().isEmpty()) {
                sb.append("\n## 刷新地点\n");
                profile.refreshLocations().stream()
                        .filter(SpiritChunkSplitter::notBlank)
                        .forEach(location -> sb.append("- ").append(location.trim()).append('\n'));
            }
        }

        // ---------- 技能 ----------
        List<Skill> movePool = spirit.movePool();
        sb.append("\n## 升级技能（").append(movePool.size()).append("）\n");
        if (movePool.isEmpty()) {
            // 实测已实装的 613 只里有 1 只（4076 伊里斯）确实没有技能数据，
            // 如实说明比编一份技能表好
            sb.append("（这批数据里该精灵没有技能记录）\n");
        } else {
            appendSkillLines(sb, movePool, maxMovesPerDoc, true);
        }

        List<Skill> legacySkills = spirit.legacyMoveSkills();
        if (includeLegacyMoves && !legacySkills.isEmpty()) {
            sb.append("\n## 血脉技能（").append(legacySkills.size()).append("）\n");
            appendSkillLines(sb, legacySkills, MAX_NAME_ONLY, false);
        }
        if (!spirit.moveStones().isEmpty()) {
            sb.append("\n## 技能石（").append(spirit.moveStones().size()).append("）\n");
            appendSkillLines(sb, spirit.moveStones(), MAX_NAME_ONLY, false);
        }

        return sb.toString().trim();
    }

    /** @param withEffect true 时带完整效果描述，false 时只列名字（用于血脉技能和技能石） */
    private static void appendSkillLines(StringBuilder sb, List<Skill> skills, int limit, boolean withEffect) {
        int shown = Math.min(limit, skills.size());
        for (int i = 0; i < shown; i++) {
            Skill skill = skills.get(i);
            sb.append(withEffect ? skill.toArchiveLine() : skill.toNameLine()).append('\n');
        }
        if (skills.size() > shown) {
            sb.append("- （其余 ").append(skills.size() - shown)
                    .append(" 个技能未列出，总数 ").append(skills.size()).append("）\n");
        }
    }

    /**
     * 按行聚合切分，并且给每个后续分块补一行身份头。
     *
     * <p>切块后第 2 块如果只剩半张技能表，检索命中它会不知道这是哪只精灵，
     * 所以非首块要在开头重复「精灵：X（属性 Y）」。
     */
    List<String> splitText(String text, int maxChars, String identityHeader) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            if (line.length() > maxChars) {
                flush(chunks, current);
                for (int start = 0; start < line.length(); start += maxChars) {
                    chunks.add(line.substring(start, Math.min(start + maxChars, line.length())));
                }
                continue;
            }
            if (current.length() > 0 && current.length() + line.length() + 1 > maxChars) {
                flush(chunks, current);
                // 续块补身份头
                current.append(identityHeader).append('\n');
            }
            if (current.length() > 0 && current.charAt(current.length() - 1) != '\n') {
                current.append('\n');
            }
            current.append(line);
        }
        flush(chunks, current);
        return chunks.stream().filter(chunk -> !chunk.isBlank()).toList();
    }

    private static String identityHeader(Spirit spirit) {
        return "精灵：" + spirit.displayName() + "（属性 " + spirit.mainTypeLabel() + "，档案续）";
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
            current.setLength(0);
        }
    }

    /**
     * 清掉源数据里混进来的零宽字符（U+200B/200C/200D/FEFF）。
     *
     * <p>游戏数据里确实有这些字符（例如「盛宴」那条特性描述），它们不可见但会：
     * 切碎正常的词、影响嵌入质量、让模型读到奇怪的文本。渲染档案时统一清掉。
     */
    private static String sanitize(String text) {
        return text.replaceAll("[\u200B\u200C\u200D\uFEFF]", "");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
