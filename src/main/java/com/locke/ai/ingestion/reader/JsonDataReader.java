package com.locke.ai.ingestion.reader;

import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.exception.IngestException;
import com.locke.ai.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取 {@code pets/{id}.json}（结构化 JSON 语料）。
 *
 * <p>只读 {@code pets/} 下的文件，<b>不读 {@code Pets.json}</b>：
 * 实测两者的 id 完全 1:1 重合（1065 vs 1065，交集 1065），
 * 而 {@code pets/{id}.json} 是自包含的完整记录，比 {@code Pets.json} 还多出
 * {@code move_pool} / {@code evolution_tree} / {@code world_profile} / {@code trait}。
 * 读两份纯属浪费。
 *
 * <p>用 Jackson 直接映射成强类型，而不是 {@code org.springframework.ai.reader.JsonReader}：
 * JsonReader 产出的是扁平 Document（一个 key 一个 Document），
 * 而结构化表 {@code spirit/skill} 需要完整对象。同一份内存对象既写表又生成向量文档，
 * 比读两遍文件省事。
 */
@Component
public class JsonDataReader {

    private static final Logger log = LoggerFactory.getLogger(JsonDataReader.class);

    private final DataRoot dataRoot;
    private final boolean implementedOnly;

    public JsonDataReader(DataRoot dataRoot,
                          @Value("${locke.ai.ingest.implemented-only:true}") boolean implementedOnly) {
        this.dataRoot = dataRoot;
        this.implementedOnly = implementedOnly;
    }

    /**
     * @param spirits       过滤后的精灵集合
     * @param warnings      单个文件解析失败等非致命问题
     * @param filesScanned  实际扫到的文件数（过滤前）
     * @param skipped       因 {@code implemented=false} 被跳过的数量
     */
    public record ReadResult(List<Spirit> spirits, List<String> warnings, int filesScanned, int skipped) {
        public ReadResult {
            spirits = List.copyOf(spirits);
            warnings = List.copyOf(warnings);
        }
    }

    public ReadResult readAll() {
        Path dir = dataRoot.petsDir();
        if (!Files.isDirectory(dir)) {
            // 整批无法开始：这属于配置错误（data-root 指错了），必须显式报错
            throw new IngestException("精灵数据目录不存在: " + dir
                    + "，请检查 locke.ai.ingest.data-root（当前为 " + dataRoot + "）");
        }

        List<Spirit> spirits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int filesScanned = 0;
        int skipped = 0;

        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();
            filesScanned = files.size();

            for (Path file : files) {
                try {
                    Spirit spirit = parse(file);
                    if (spirit == null || spirit.id() == null) {
                        warnings.add(file.getFileName() + ": 缺少 id，已跳过");
                        continue;
                    }
                    if (implementedOnly && !Boolean.TRUE.equals(spirit.implemented())) {
                        // 452 只未实装（测试/未上线）精灵会被这里过滤掉，
                        // 它们的文案是策划占位内容，混进知识库会答错
                        skipped++;
                        continue;
                    }
                    spirits.add(spirit);
                } catch (RuntimeException e) {
                    // 单个文件坏掉不中断整批
                    String warning = file.getFileName() + ": " + e.getMessage();
                    log.warn("跳过解析失败的精灵文件 -> {}", warning);
                    warnings.add(warning);
                }
            }
        } catch (IOException e) {
            throw new IngestException("遍历精灵数据目录失败: " + dir, e);
        }

        log.info("精灵语料读取完成：扫到 {} 个文件，入库 {} 只，因未实装跳过 {} 只，警告 {} 条",
                filesScanned, spirits.size(), skipped, warnings.size());
        return new ReadResult(spirits, warnings, filesScanned, skipped);
    }

    public Spirit parse(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return JsonUtils.fromJson(json, Spirit.class);
        } catch (IOException e) {
            throw new IngestException("读取精灵文件失败: " + file, e, file.getFileName().toString());
        }
    }

    /** 单只精灵的来源标识，形如 {@code pets/3001.json}，用于确定性 id 和引用出处。 */
    public String sourceId(Spirit spirit) {
        return dataRoot.sourceId("pets/" + spirit.id() + ".json");
    }
}
