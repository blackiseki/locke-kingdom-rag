package com.locke.ai.service;

import com.locke.ai.domain.dto.IngestResult;
import com.locke.ai.domain.entity.Skill;
import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.exception.IngestException;
import com.locke.ai.ingestion.reader.DataRoot;
import com.locke.ai.ingestion.reader.ItemDataReader;
import com.locke.ai.ingestion.reader.JsonDataReader;
import com.locke.ai.ingestion.reader.MoveDataReader;
import com.locke.ai.ingestion.reader.ReferenceDataReader;
import com.locke.ai.ingestion.reader.WikiMarkdownReader;
import com.locke.ai.ingestion.splitter.SpiritChunkSplitter;
import com.locke.ai.ingestion.transformer.DocumentEnricher;
import com.locke.ai.repository.SpiritRepository;
import com.locke.ai.repository.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入库编排。
 *
 * <h3>五条流水线</h3>
 * <pre>
 * pets      : pets/{id}.json -> 精灵档案文本 -> DocumentEnricher -> 向量库
 *                            -> spirit / skill / spirit_skill 三张表（全量重建）
 * moves     : moves.json     -> 技能文档                          -> 向量库
 * items     : items.json     -> 道具文档                          -> 向量库
 * reference : types/game_terms/personalities/magic_items -> 文档   -> 向量库
 *                            -> element_type 表（全量重建）
 * wiki      : BinData/*.md   -> 按标题切块 -> 按 token 兜底切块 -> 向量库
 * </pre>
 *
 * <p><b>DocumentEnricher 永远是最后一步</b>，因为它负责写确定性 id——
 * 详见该类的注释（提前生成 id 会被 TextSplitter 覆盖或撞车，导致静默丢数据
 * 或者 uuid 列写库报错）。
 *
 * <h3>结构化表是全量重建</h3>
 * {@code pets} 分支会先清空 {@code spirit / skill / spirit_skill} 再写。
 * 所以它需要 {@code moves.json} 的技能总表一起来（否则 skill 表里没有总表那 475 条），
 * 这也是为什么 {@code pets} 分支会顺带读一次 {@code moves.json}。
 * 相应地，单独跑 {@code moves} 只写向量、不动结构化表。
 *
 * <p>{@code implemented=false} 的 452 只测试/未上线精灵默认被过滤掉，
 * 它们的文案是策划占位内容（例如「这是一只测试宠物，如果你在不该看到的地方看到了它，
 * 请联系策划」），进知识库只会污染答案。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final List<String> VALID_SOURCES =
            List.of("all", "pets", "moves", "items", "reference", "wiki");

    private final JsonDataReader jsonDataReader;
    private final MoveDataReader moveDataReader;
    private final ItemDataReader itemDataReader;
    private final ReferenceDataReader referenceDataReader;
    private final WikiMarkdownReader wikiMarkdownReader;
    private final SpiritChunkSplitter spiritChunkSplitter;
    private final DocumentEnricher documentEnricher;
    private final TokenTextSplitter tokenTextSplitter;
    private final VectorRepository vectorRepository;
    private final SpiritRepository spiritRepository;
    private final DataRoot dataRoot;
    private final int batchSize;

    public IngestService(JsonDataReader jsonDataReader,
                         MoveDataReader moveDataReader,
                         ItemDataReader itemDataReader,
                         ReferenceDataReader referenceDataReader,
                         WikiMarkdownReader wikiMarkdownReader,
                         SpiritChunkSplitter spiritChunkSplitter,
                         DocumentEnricher documentEnricher,
                         TokenTextSplitter tokenTextSplitter,
                         VectorRepository vectorRepository,
                         SpiritRepository spiritRepository,
                         DataRoot dataRoot,
                         @Value("${locke.ai.ingest.batch-size:50}") int batchSize) {
        this.jsonDataReader = jsonDataReader;
        this.moveDataReader = moveDataReader;
        this.itemDataReader = itemDataReader;
        this.referenceDataReader = referenceDataReader;
        this.wikiMarkdownReader = wikiMarkdownReader;
        this.spiritChunkSplitter = spiritChunkSplitter;
        this.documentEnricher = documentEnricher;
        this.tokenTextSplitter = tokenTextSplitter;
        this.vectorRepository = vectorRepository;
        this.spiritRepository = spiritRepository;
        this.dataRoot = dataRoot;
        // 0 或负数会让分批循环死循环
        this.batchSize = batchSize <= 0 ? 50 : batchSize;
    }

    /**
     * 执行入库。
     *
     * @param source {@code all} / {@code pets} / {@code moves} / {@code items} / {@code reference} / {@code wiki}
     * @param dryRun true 时只解析和切分，不写库也不写结构化表
     */
    public IngestResult ingest(String source, boolean dryRun) {
        long startNanos = System.nanoTime();
        String target = (source == null || source.isBlank()) ? "all" : source.trim().toLowerCase();
        if (!VALID_SOURCES.contains(target)) {
            throw new IngestException("不支持的 source 值: " + source + "（可选 " + String.join(" / ", VALID_SOURCES) + "）");
        }

        Counters counters = new Counters();
        List<String> warnings = new ArrayList<>();

        // 先做一次根目录检查：否则最容易出现的情况是「配错了 data-root」，
        // 却在某个具体文件上抛「moves.json 不存在」，让人以为只是缺个文件
        Path root = dataRoot.root();
        if (!Files.isDirectory(root)) {
            throw new IngestException("语料根目录不存在: " + root
                    + "，请检查 locke.ai.ingest.data-root（可用 LOCKE_DATA_ROOT 环境变量覆盖）");
        }

        boolean wantPets = target.equals("all") || target.equals("pets");
        boolean wantMoves = target.equals("all") || target.equals("moves");
        boolean wantItems = target.equals("all") || target.equals("items");
        boolean wantReference = target.equals("all") || target.equals("reference");
        boolean wantWiki = target.equals("all") || target.equals("wiki");

        // moves.json 在 pets 分支（写结构化表要用技能总表）和 moves 分支（写向量）都要用，
        // 只读一次，避免重复解析 240KB
        List<Skill> catalogSkills = List.of();
        if (wantPets || wantMoves) {
            try {
                MoveDataReader.ReadResult moves = moveDataReader.readAll();
                catalogSkills = moves.skills();
                warnings.addAll(moves.warnings());
            } catch (IngestException e) {
                if (wantMoves) {
                    // 明确要求入库技能，缺文件就是错误
                    throw e;
                }
                // 只是顺带读来喂 skill 表：缺了不该让 pets 分支整体失败，
                // 代价是 skill 表里没有那 475 条技能总表数据
                log.warn("技能总表不可用，skill 表将缺少总表数据: {}", e.getMessage());
                warnings.add("技能总表不可用，skill 表将缺少总表数据: " + e.getMessage());
            }
        }

        if (wantPets) {
            ingestPets(counters, warnings, dryRun, catalogSkills);
        }
        if (wantMoves) {
            ingestMoves(counters, warnings, dryRun, catalogSkills);
        }
        if (wantItems) {
            ingestItems(counters, warnings, dryRun);
        }
        if (wantReference) {
            ingestReference(counters, warnings, dryRun);
        }
        if (wantWiki) {
            ingestWiki(counters, warnings, dryRun);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        int vectorTotal = dryRun ? -1 : vectorRepository.count();

        log.info("入库完成 source={} dryRun={} 文件={} 文档={} 切块={} 写入向量={} 精灵={} 技能={} 关联={} 属性={} 跳过={} 警告={} 耗时={}ms",
                target, dryRun, counters.files, counters.documents, counters.chunks, counters.written,
                counters.spirits, counters.skills, counters.links, counters.elementTypes,
                counters.skipped, warnings.size(), elapsedMs);

        return new IngestResult(dryRun, counters.files, counters.documents, counters.chunks, counters.written,
                counters.spirits, counters.skills, counters.links, counters.elementTypes,
                counters.skipped, vectorTotal, warnings, elapsedMs);
    }

    // ============================================================
    // pets
    // ============================================================

    private void ingestPets(Counters counters, List<String> warnings, boolean dryRun, List<Skill> catalogSkills) {
        JsonDataReader.ReadResult read = jsonDataReader.readAll();
        warnings.addAll(read.warnings());

        counters.files += read.filesScanned();
        counters.skipped += read.skipped();
        counters.documents += read.spirits().size();

        if (!dryRun) {
            SpiritRepository.StructuredCounts counts = spiritRepository.replaceAll(read.spirits(), catalogSkills);
            counters.spirits = counts.spirits();
            counters.skills = counts.skills();
            counters.links = counts.links();
        }

        List<Document> documents = new ArrayList<>();
        for (Spirit spirit : read.spirits()) {
            String sourceId = jsonDataReader.sourceId(spirit);
            documents.addAll(documentEnricher.enrichWithSpirit(
                    spiritChunkSplitter.split(spirit, sourceId), spirit, sourceId));
        }
        counters.chunks += documents.size();
        if (!dryRun) {
            counters.written += writeInBatches(documents);
        }
    }

    // ============================================================
    // moves
    // ============================================================

    private void ingestMoves(Counters counters, List<String> warnings, boolean dryRun, List<Skill> catalogSkills) {
        if (catalogSkills.isEmpty()) {
            warnings.add("技能总表为空，跳过技能文档");
            return;
        }
        String source = dataRoot.sourceId("moves.json");
        counters.documents += catalogSkills.size();

        List<Document> documents = new ArrayList<>(catalogSkills.size());
        for (Skill skill : catalogSkills) {
            documents.add(Document.builder()
                    .text(renderSkillDocument(skill))
                    .metadata(Map.of("skillId", skill.id(), "skillName", skill.displayName()))
                    .build());
        }
        writeAndCount(counters, documents, source, DocumentEnricher.DOC_TYPE_SKILL, dryRun);
    }

    /** 独立成篇的技能文档：比精灵档案里的单行版多带上分类和英文名，方便检索兜底。 */
    private static String renderSkillDocument(Skill skill) {
        StringBuilder sb = new StringBuilder("技能：").append(skill.displayName()).append('\n');
        if (skill.name() != null && !skill.name().equals(skill.displayName())) {
            sb.append("英文名：").append(skill.name()).append('\n');
        }
        sb.append("属性：").append(skill.typeLabel()).append('\n');
        sb.append("类别：").append(skill.categoryLabel()).append('\n');
        if (skill.power() != null) {
            sb.append("威力：").append(skill.power()).append('\n');
        }
        if (skill.energyCost() != null) {
            sb.append("能耗：").append(skill.energyCost()).append('\n');
        }
        String effect = skill.effect();
        if (effect != null && !effect.isBlank()) {
            sb.append("效果：").append(effect.trim());
        }
        return sb.toString().trim();
    }

    // ============================================================
    // items
    // ============================================================

    private void ingestItems(Counters counters, List<String> warnings, boolean dryRun) {
        ItemDataReader.ReadResult read = itemDataReader.readAll();
        warnings.addAll(read.warnings());
        counters.files += 1;
        counters.documents += read.documents().size();
        writeGroupedBySource(counters, read.documents(), DocumentEnricher.DOC_TYPE_ITEM, dryRun);
    }

    // ============================================================
    // reference（属性 / 术语 / 性格 / 血脉魔法）
    // ============================================================

    private void ingestReference(Counters counters, List<String> warnings, boolean dryRun) {
        ReferenceDataReader.ReadResult read = referenceDataReader.readAll();
        warnings.addAll(read.warnings());
        counters.files += 4;
        counters.documents += read.documents().size();

        if (!dryRun && !read.elementTypes().isEmpty()) {
            counters.elementTypes = spiritRepository.replaceElementTypes(read.elementTypes());
        }
        writeGroupedBySource(counters, read.documents(), DocumentEnricher.DOC_TYPE_TERM, dryRun);
    }

    // ============================================================
    // wiki（BinData/*.md 数据字典）
    // ============================================================

    private void ingestWiki(Counters counters, List<String> warnings, boolean dryRun) {
        WikiMarkdownReader.ReadResult read = wikiMarkdownReader.readAll();
        warnings.addAll(read.warnings());
        counters.files += read.entries().size();

        List<Document> documents = new ArrayList<>();
        for (WikiMarkdownReader.Entry entry : read.entries()) {
            counters.documents += entry.documents().size();
            // 先按标题切（reader 已经做了），再按 token 兜底切长段落；最后才补 id
            documents.addAll(documentEnricher.enrich(
                    tokenTextSplitter.apply(entry.documents()),
                    wikiMarkdownReader.sourceId(entry.source()),
                    DocumentEnricher.DOC_TYPE_DATA_DICTIONARY));
        }
        counters.chunks += documents.size();
        if (!dryRun) {
            counters.written += writeInBatches(documents);
        }
    }

    // ============================================================
    // 公共
    // ============================================================

    /**
     * 按元数据里的 {@code source} 分组后再补 id。
     *
     * <p>必须分组：{@code reference} 分支的文档来自 4 个不同文件，
     * 如果混在一起补 id，块的序号就跨文件了，而且 source 元数据也会被写成同一个值。
     */
    private void writeGroupedBySource(Counters counters, List<Document> documents, String defaultDocType, boolean dryRun) {
        Map<String, List<Document>> bySource = new LinkedHashMap<>();
        for (Document document : documents) {
            Object source = document.getMetadata().get(DocumentEnricher.META_SOURCE);
            bySource.computeIfAbsent(source == null ? "unknown" : source.toString(), key -> new ArrayList<>())
                    .add(document);
        }
        for (Map.Entry<String, List<Document>> entry : bySource.entrySet()) {
            List<Document> enriched = documentEnricher.enrich(entry.getValue(), entry.getKey(), defaultDocType);
            counters.chunks += enriched.size();
            if (!dryRun) {
                counters.written += writeInBatches(enriched);
            }
        }
    }

    private void writeAndCount(Counters counters, List<Document> documents, String source,
                               String docType, boolean dryRun) {
        List<Document> enriched = documentEnricher.enrich(documents, source, docType);
        counters.chunks += enriched.size();
        if (!dryRun) {
            counters.written += writeInBatches(enriched);
        }
    }

    /** 分批写向量库：一次提交太多会顶到库的批处理上限，也会拉长单次事务。 */
    private int writeInBatches(List<Document> chunks) {
        int written = 0;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<Document> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            vectorRepository.save(batch);
            written += batch.size();
        }
        return written;
    }

    /** 可变计数器，避免在多个私有方法之间传一长串参数。 */
    private static final class Counters {
        private int files;
        private int documents;
        private int chunks;
        private int written;
        private int spirits;
        private int skills;
        private int links;
        private int elementTypes;
        private int skipped;
    }
}
