package com.locke.ai.ingestion.reader;

import com.locke.ai.domain.entity.Skill;
import com.locke.ai.exception.IngestException;
import com.locke.ai.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取 {@code moves.json}（技能总表，475 条）。
 *
 * <p><b>注意它和精灵内嵌技能是两套 id 空间</b>：实测 {@code moves.json} 的 id
 * 在 1~3643，而精灵 {@code move_pool} 里的技能是 7 位数，两者交集为 0。
 * 所以这份数据不是「精灵技能的子集」，而是一份独立的技能总表，
 * 值得单独入向量库（问「XX 技能是什么效果」时命中的就是它）。
 *
 * <p>另一个坑：{@code moves.json} 的记录<b>没有 {@code icon_id} 字段</b>，
 * 且 {@code move_type} 有 2 条为 null、{@code power} 有 178 条为 null。
 * {@link Skill} 已经把这些都当成可空处理，不需要额外分支。
 */
@Component
public class MoveDataReader {

    private static final Logger log = LoggerFactory.getLogger(MoveDataReader.class);

    private final DataRoot dataRoot;

    public MoveDataReader(DataRoot dataRoot) {
        this.dataRoot = dataRoot;
    }

    public record ReadResult(List<Skill> skills, List<String> warnings) {
        public ReadResult {
            skills = List.copyOf(skills);
            warnings = List.copyOf(warnings);
        }
    }

    public ReadResult readAll() {
        Path file = dataRoot.file("moves.json");
        if (!Files.isRegularFile(file)) {
            throw new IngestException("技能总表不存在: " + file);
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<Skill> all = JsonUtils.fromJsonList(json, Skill.class);
            List<Skill> skills = all.stream()
                    .filter(skill -> skill.id() != null && skill.displayName() != null && !skill.displayName().isBlank())
                    .toList();
            log.info("技能总表读取完成：{}/{} 条有效", skills.size(), all.size());
            return new ReadResult(skills, List.of());
        } catch (IOException e) {
            throw new IngestException("读取技能总表失败: " + file, e, "moves.json");
        }
    }
}
