package com.locke.ai.ingestion;

import com.locke.ai.domain.entity.Skill;
import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.domain.enums.ElementType;
import com.locke.ai.ingestion.reader.DataRoot;
import com.locke.ai.ingestion.reader.JsonDataReader;
import com.locke.ai.ingestion.reader.MoveDataReader;
import com.locke.ai.ingestion.reader.ReferenceDataReader;
import com.locke.ai.ingestion.splitter.SpiritChunkSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 拿<b>真实游戏数据</b>（{@code rocom.aoe.top} 导出的 1065 个精灵文件）验证解析与渲染。
 *
 * <p>这是整个改造里最有价值的一个测试：它把「字段名对不对、{@code localized.zh}
 * 的两种形状有没有都处理到、属性枚举和真实属性名能不能对上、有没有哪只精灵
 * 会把渲染搞崩」一次性验掉，而且不需要数据库、不需要 Ollama。
 *
 * <p>数据目录不存在时自动跳过（{@code assumeTrue}），所以在没有这份数据的机器上
 * 构建不会失败。要指向别的位置：
 * <pre>
 *   ./mvnw test -Dlocke.data.root=D:/somewhere/data
 * </pre>
 *
 * <p>断言用的是区间而不是精确值（例如精灵数 600~700 而不是 ==613），
 * 这样数据包更新后测试不会无谓地红，但如果解析真的坏了（变成 0 或者 1065）
 * 立刻就能发现。
 */
class RealDataParseTest {

    private static final Logger log = LoggerFactory.getLogger(RealDataParseTest.class);

    private static final Path DATA_ROOT = Path.of(
            System.getProperty("locke.data.root", "./data"));

    private DataRoot dataRoot() {
        return new DataRoot(DATA_ROOT.toString());
    }

    @Test
    @DisplayName("真实精灵数据：只入已实装的、中文名和属性都解析正确")
    void shouldParseRealPets() {
        assumeTrue(Files.isDirectory(DATA_ROOT.resolve("pets")), "跳过：没有真实数据 " + DATA_ROOT);

        JsonDataReader.ReadResult read = new JsonDataReader(dataRoot(), true).readAll();

        log.info("真实数据解析结果：扫到 {} 个文件，入库 {} 只，跳过 {} 只未实装，警告 {} 条",
                read.filesScanned(), read.spirits().size(), read.skipped(), read.warnings().size());

        assertThat(read.filesScanned()).isEqualTo(1065);
        assertThat(read.spirits().size()).isBetween(600, 700);
        assertThat(read.skipped()).isBetween(400, 500);
        assertThat(read.warnings()).as("真实数据不该有解析失败的文件").isEmpty();

        // 每一只都必须能拿到中文名和主属性
        for (Spirit spirit : read.spirits()) {
            assertThat(spirit.displayName()).as("id=%s", spirit.id()).isNotBlank();
            assertThat(spirit.mainType()).as("id=%s 没有主属性", spirit.id()).isNotNull();
            // 这条最关键：验证 ElementType 枚举覆盖了真实数据里的全部属性名，
            // 一旦数据里出现新属性，这里会失败而不是静默变成「未知」
            assertThat(spirit.mainElement())
                    .as("id=%s 属性 %s 无法识别", spirit.id(), spirit.mainType().name())
                    .isNotEqualTo(ElementType.UNKNOWN);
        }

        // 种族值：实测 613 只里有 28 只（4000+ 的首领/终极形态）六项全为 0，
        // 所以不能要求每只都为正，但绝大多数必须能读出合理数值
        long withStats = read.spirits().stream().filter(s -> s.baseStatTotal() > 0).count();
        log.info("种族值可读的精灵：{}/{}（其余 {} 只是首领形态，PETBASE 里本就没有种族值）",
                withStats, read.spirits().size(), read.spirits().size() - withStats);
        assertThat(withStats).as("种族值大面积读不出来，说明字段名或类型绑定错了")
                .isGreaterThan(read.spirits().size() * 9L / 10L);

        // 实测只有 1 只（4076 伊里斯）没有技能数据，允许少量
        long emptyMovePool = read.spirits().stream().filter(s -> s.movePool().isEmpty()).count();
        assertThat(emptyMovePool).as("技能为空的精灵数量异常").isLessThanOrEqualTo(5);
        // 血脉技能：JSON 外层是 {monster_id, type_id, move_id, move} 包装对象，
        // 真正的技能在 move 里。这里守住一个真实踩过的坑——曾经把外层直接绑成 Skill，
        // 导致所有字段为 null，档案里渲染出 18 行「null」，模型都反过来提醒了我。
        long petsWithLegacy = read.spirits().stream().filter(s -> !s.legacyMoveSkills().isEmpty()).count();
        assertThat(petsWithLegacy).as("一只都解析不出血脉技能，说明 LegacyMove 的字段路径错了").isPositive();
        read.spirits().stream().flatMap(s -> s.legacyMoveSkills().stream()).limit(100)
                .forEach(skill -> assertThat(skill.displayName()).as("血脉技能名不该为 null").isNotBlank());

        // 进化等级要从中文条件里解析出来：实测 333/613 有等级进化
        long withEvolveLevel = read.spirits().stream().filter(s -> s.evolveLevel() != null).count();
        log.info("解析出进化等级的精灵：{}/{}", withEvolveLevel, read.spirits().size());
        assertThat(withEvolveLevel).as("没有一只解析出进化等级，说明正则或字段路径错了")
                .isGreaterThan(read.spirits().size() / 3L);
    }

    @Test
    @DisplayName("真实精灵数据：613 只全部能渲染成档案，并报告实际语料规模")
    void shouldRenderAllRealPets() {
        assumeTrue(Files.isDirectory(DATA_ROOT.resolve("pets")), "跳过：没有真实数据 " + DATA_ROOT);

        JsonDataReader reader = new JsonDataReader(dataRoot(), true);
        List<Spirit> spirits = reader.readAll().spirits();
        SpiritChunkSplitter splitter = new SpiritChunkSplitter(25, true);

        int documents = 0;
        long totalChars = 0;
        int maxChars = 0;
        String maxCharsName = null;

        for (Spirit spirit : spirits) {
            List<Document> chunks = splitter.split(spirit, reader.sourceId(spirit));
            assertThat(chunks).as("id=%s 渲染不出文档", spirit.id()).isNotEmpty();
            documents += chunks.size();
            for (Document chunk : chunks) {
                int length = chunk.getText().length();
                totalChars += length;
                if (length > maxChars) {
                    maxChars = length;
                    maxCharsName = spirit.displayName();
                }
            }
        }

        log.info("精灵档案语料规模：{} 只精灵 -> {} 篇文档，共 {} 字，单篇最长 {} 字（{}）",
                spirits.size(), documents, totalChars, maxChars, maxCharsName);

        assertThat(documents).isGreaterThanOrEqualTo(spirits.size());
        // 单篇不能超过 SpiritChunkSplitter.MAX_CHARS，否则 Ollama 的 num-ctx 会截断
        assertThat(maxChars).as("有档案超过切分上限").isLessThanOrEqualTo(3000);
    }

    @Test
    @DisplayName("真实参考数据：属性克制 19 条、技能总表 475 条，形状都对得上")
    void shouldParseRealReferenceData() {
        assumeTrue(Files.isDirectory(DATA_ROOT), "跳过：没有真实数据 " + DATA_ROOT);

        ReferenceDataReader.ReadResult reference = new ReferenceDataReader(dataRoot()).readAll();
        assertThat(reference.elementTypes()).hasSize(19);
        // types.json 的 localized.zh 是字符串形式，label() 必须能取到中文
        assertThat(reference.elementTypes())
                .allSatisfy(type -> assertThat(type.label()).as("属性 id=%s", type.id()).isNotBlank());
        assertThat(reference.elementTypes())
                .anySatisfy(type -> assertThat(type.name()).isEqualTo("Ground"));
        assertThat(reference.warnings()).isEmpty();

        MoveDataReader.ReadResult moves = new MoveDataReader(dataRoot()).readAll();
        assertThat(moves.skills().size()).isBetween(400, 600);
        assertThat(moves.skills()).allSatisfy(skill -> assertThat(skill.displayName()).isNotBlank());
        // moves.json 里确实有 power 为 null 的记录（实测 178 条），必须容忍而不是崩
        assertThat(moves.skills()).anySatisfy(skill -> assertThat(skill.power()).isNull());
        assertThat(moves.skills()).anySatisfy(skill -> assertThat(skill.moveType()).isNull());
    }
}
