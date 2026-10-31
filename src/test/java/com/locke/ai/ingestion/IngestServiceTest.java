package com.locke.ai.ingestion;

import com.locke.ai.domain.dto.IngestResult;
import com.locke.ai.domain.entity.Spirit;
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
import com.locke.ai.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 入库流水线测试。
 *
 * <p>用 {@link TempDir} 造一份<b>形状和真实数据一致</b>的小样本
 * （字段名、{@code localized.zh} 的两种形状、可空字段都照抄真实数据），
 * 只有两个仓库是 mock——这样不需要数据库和 Ollama 就能把
 * 「解析 → 渲染档案 → 补元数据 → 分批写入」整条链路跑通。
 *
 * <p>真实数据的完整解析验证在 {@code RealDataParseTest} 里。
 */
class IngestServiceTest {

    private static final String PET_JSON = """
            {
              "id": 3001,
              "species_id": 2,
              "name": "miaomiao",
              "form": "喵喵",
              "main_type": {"id": 2, "name": "Grass", "localized": {"zh": "草"}},
              "sub_type": null,
              "default_legacy_type": {"id": 2, "name": "Grass", "localized": {"zh": "草"}},
              "leader_potential": false,
              "is_leader_form": false,
              "preferred_attack_style": "Both",
              "localized": {"zh": {"name": "喵喵"}},
              "implemented": true,
              "base_hp": 65, "base_phy_atk": 66, "base_mag_atk": 66,
              "base_phy_def": 49, "base_mag_def": 91, "base_spd": 33,
              "evolves_from_id": null,
              "species": {"id": 2, "name": "miaomiao", "localized": {"zh": "喵喵"}},
              "trait": {"id": 1, "name": "茂盛", "description": "茂盛。",
                        "localized": {"zh": {"name": "茂盛", "description": "茂盛。"}}},
              "move_pool": [
                {"id": 7020370, "name": "猛烈撞击", "icon_id": "700001",
                 "move_type": {"id": 1, "name": "Normal", "localized": {"zh": "普通"}},
                 "localized": {"zh": {"name": "猛烈撞击", "description": "对敌方精灵造成物理伤害。"}},
                 "move_category": "Physical Attack", "energy_cost": 1, "power": 65,
                 "description": "deals physical damage"},
                {"id": 7020371, "name": "光合作用", "icon_id": "700002",
                 "move_type": {"id": 2, "name": "Grass", "localized": {"zh": "草"}},
                 "localized": {"zh": {"name": "光合作用", "description": "恢复自身生命值。"}},
                 "move_category": "Status", "energy_cost": 2, "power": null,
                 "description": "heals"}
              ],
              "move_stones": [],
              "legacy_moves": [],
              "evolution_tree": {
                "stages": [
                  {"depth": 0, "is_leader_stage": false, "monsters": [
                    {"id": 3001, "species_id": 2, "name": "miaomiao", "form": "喵喵",
                     "localized": {"zh": {"name": "喵喵"}}, "is_leader_form": false,
                     "main_type": {"id": 2, "name": "Grass", "localized": {"zh": "草"}},
                     "sub_type": null, "evolution_conditions": []}]},
                  {"depth": 1, "is_leader_stage": false, "monsters": [
                    {"id": 3002, "species_id": 2, "name": "miaowu", "form": "喵呜",
                     "localized": {"zh": {"name": "喵呜"}}, "is_leader_form": false,
                     "main_type": {"id": 2, "name": "Grass", "localized": {"zh": "草"}},
                     "sub_type": null, "evolution_conditions": ["等级达到 16 级"]}]}
                ],
                "max_depth": 1, "total_unique_monsters": 2, "species_id": 2,
                "current_monster_id": 3001
              },
              "world_profile": {
                "type_desc": null, "description_habitat": "常见于草地与森林边缘。",
                "introduction": "好奇心旺盛的草系精灵。",
                "refresh_locations": ["草原"], "movement_type": null,
                "classis_id": 3, "classis_name": "猫咪类精灵", "handbook_area_ids": []
              },
              "catch_info": {"catch_threshold": 1000, "catch_guarant_rate": 1700, "catch_ball_level": 1}
            }
            """;

    /** 未实装的精灵：必须被 implemented-only 过滤掉。 */
    private static final String UNIMPLEMENTED_PET_JSON = """
            {
              "id": 10000,
              "name": "10000",
              "form": "default",
              "main_type": {"id": 20, "name": "Unknown", "localized": {"zh": "未知"}},
              "sub_type": null,
              "localized": {"zh": {"name": "随机精灵"}},
              "implemented": false,
              "move_pool": [], "move_stones": [], "legacy_moves": [],
              "world_profile": {"introduction": "这是一只测试宠物。", "refresh_locations": [],
                                "classis_id": 3, "classis_name": "猫咪类精灵", "handbook_area_ids": []}
            }
            """;

    private static final String MOVES_JSON = """
            [
              {"id": 1, "name": "Focus", "localized": {"zh": {"name": "聚能", "description": "本回合不做其他行动，恢复5点能量。"}},
               "move_type": null, "move_category": "Status", "energy_cost": 0, "power": null,
               "description": "restores 5 energy"},
              {"id": 3, "name": "Control",
               "localized": {"zh": {"name": "操控", "description": "降低对手速度。"}},
               "move_type": {"id": 1, "name": "Normal", "localized": {"zh": "普通"}},
               "move_category": "Status", "energy_cost": 1, "power": null, "description": "lowers speed"}
            ]
            """;

    private static final String ITEMS_JSON = """
            [
              {"id": 100002, "name": "普通咕噜球", "icon_id": "100002",
               "description": "与精灵缔结契约的魔法道具。", "flavor_text": "曾是贵族的秘宝。",
               "category": "咕噜球", "type_desc": "咕噜球", "quality": 3, "quality_label": "精良",
               "acquire_ways": ["炼金造物获得"], "related_pets": [], "recipes": []}
            ]
            """;

    private static final String TYPES_JSON = """
            [
              {"id": 2, "name": "Grass", "localized": {"zh": "草"},
               "vulnerable_to": ["Flying", "Fire"], "resistant_to": ["Water", "Light"]},
              {"id": 3, "name": "Fire", "localized": {"zh": "火"},
               "vulnerable_to": ["Ground", "Water"], "resistant_to": ["Grass"]}
            ]
            """;

    private static final String TERMS_JSON = """
            [
              {"id": 1, "key": "Marks", "description": "A status effect on the whole team.",
               "localized": {"zh": {"name": "印记", "description": "常驻在全队的效果，精灵下场后不会消失。"}},
               "sort_order": 0}
            ]
            """;

    private static final String PERSONALITIES_JSON = """
            [
              {"id": 1, "name": "Timid", "hp_mod_pct": 0, "phy_atk_mod_pct": -10,
               "mag_atk_mod_pct": 0, "phy_def_mod_pct": 0, "mag_def_mod_pct": 0,
               "spd_mod_pct": 10, "localized": {"zh": "胆小"}}
            ]
            """;

    private static final String MAGIC_ITEMS_JSON = """
            [
              {"id": 1, "name": "Flame", "description": "Flame magic.",
               "localized": {"zh": {"name": "烈焰", "description": "血脉魔法，造成火属性伤害。"}}}
            ]
            """;

    private static final String WIKI_MD = """
            # 战斗、技能与属性

            说明：战斗规则、技能效果、Buff、数值判定与属性克制配置。

            - BATTLE_CONF.json：战斗的战斗规则配置。结构：RocoDataRows；约 4841 条记录；
              首记录字段：id、name、type、battle_model。
            - SKILL_CONF.json：技能效果配置。
            """;

    @TempDir
    Path tempDir;

    private SpiritRepository spiritRepository;
    private VectorRepository vectorRepository;
    private DataRoot dataRoot;

    @BeforeEach
    void setUp() throws IOException {
        dataRoot = new DataRoot(tempDir.toString());
        spiritRepository = mock(SpiritRepository.class);
        vectorRepository = mock(VectorRepository.class);
        when(vectorRepository.count()).thenReturn(0);

        Path pets = Files.createDirectories(tempDir.resolve("pets"));
        Files.writeString(pets.resolve("3001.json"), PET_JSON, StandardCharsets.UTF_8);
        Files.writeString(pets.resolve("10000.json"), UNIMPLEMENTED_PET_JSON, StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("moves.json"), MOVES_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("items.json"), ITEMS_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("types.json"), TYPES_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("game_terms.json"), TERMS_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("personalities.json"), PERSONALITIES_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("magic_items.json"), MAGIC_ITEMS_JSON, StandardCharsets.UTF_8);

        Path binData = Files.createDirectories(tempDir.resolve("BinData"));
        Files.writeString(binData.resolve("3.战斗、技能与属性.md"), WIKI_MD, StandardCharsets.UTF_8);
        // 同目录放一个 .json，验证 reader 只吃 .md、不会误读配置表
        Files.writeString(binData.resolve("BATTLE_CONF.json"), "{\"RocoDataRows\":[]}", StandardCharsets.UTF_8);
    }

    private IngestService newService() {
        int chunkSize = 100;
        return new IngestService(
                new JsonDataReader(dataRoot, true),
                new MoveDataReader(dataRoot),
                new ItemDataReader(dataRoot),
                new ReferenceDataReader(dataRoot),
                new WikiMarkdownReader(dataRoot),
                new SpiritChunkSplitter(25, true),
                new DocumentEnricher(),
                TokenTextSplitter.builder()
                        .withChunkSize(chunkSize)
                        .withMinChunkSizeChars(chunkSize / 4)
                        .withMinChunkLengthToEmbed(1)
                        .withMaxNumChunks(500)
                        .withKeepSeparator(true)
                        .build(),
                vectorRepository,
                spiritRepository,
                dataRoot,
                50);
    }

    @Test
    @DisplayName("端到端解析：中文名、属性、种族值、技能、进化链都要读对")
    void shouldParseRealShapedPetJson() {
        JsonDataReader.ReadResult read = new JsonDataReader(dataRoot, true).readAll();

        assertThat(read.filesScanned()).isEqualTo(2);
        assertThat(read.spirits()).hasSize(1);
        assertThat(read.skipped()).isEqualTo(1);

        Spirit spirit = read.spirits().get(0);
        // localized.zh 是对象 -> 取 name
        assertThat(spirit.displayName()).isEqualTo("喵喵");
        // main_type.localized.zh 是字符串 -> 直接当标签用（这是最容易踩的坑）
        assertThat(spirit.mainTypeLabel()).isEqualTo("草");
        assertThat(spirit.subType()).isNull();
        assertThat(spirit.baseStatTotal()).isEqualTo(65 + 66 + 66 + 49 + 91 + 33);
        assertThat(spirit.groupId()).isEqualTo(2);
        assertThat(spirit.trait().label()).isEqualTo("茂盛");
        // power 为 null 的变化技能不能炸
        assertThat(spirit.movePool()).hasSize(2);
        assertThat(spirit.movePool().get(1).power()).isNull();
        assertThat(spirit.movePool().get(1).categoryLabel()).isEqualTo("变化");
        // 进化链：第 1 阶段带「等级达到 16 级」，且它是进入下一形态的条件
        assertThat(spirit.evolutionLines()).hasSize(2);
        assertThat(spirit.evolutionLines().get(1)).contains("喵呜").contains("等级达到 16 级");
        // 当前形态（第 0 阶段）没有条件，所以自身进化等级为空
        assertThat(spirit.evolveLevel()).isNull();
        assertThat(spirit.worldProfile().classisName()).isEqualTo("猫咪类精灵");
        assertThat(spirit.worldProfile().introduction()).isEqualTo("好奇心旺盛的草系精灵。");
    }

    @Test
    @DisplayName("精灵档案要把属性、种族值、技能效果和进化链都渲染进正文")
    void archiveShouldContainKeyFacts() {
        Spirit spirit = new JsonDataReader(dataRoot, true).readAll().spirits().get(0);
        String source = "pets/3001.json";
        // 注意：id 不是 splitter 写的，而是 DocumentEnricher 最后一步写的，
        // 所以这里要串上 enricher 才能断言确定性 id
        List<org.springframework.ai.document.Document> documents = new DocumentEnricher()
                .enrichWithSpirit(new SpiritChunkSplitter(25, true).split(spirit, source), spirit, source);

        assertThat(documents).hasSize(1);
        String text = documents.get(0).getText();
        assertThat(text)
                .contains("# 喵喵")
                .contains("属性：草")
                .contains("种族值")
                .contains("猫咪类精灵")
                .contains("好奇心旺盛的草系精灵")
                .contains("猛烈撞击")
                .contains("对敌方精灵造成物理伤害。")
                .contains("## 进化链")
                .contains("等级达到 16 级");
        // 确定性 id：同一来源重复入库会覆盖而不是新增
        assertThat(documents.get(0).getId()).isEqualTo(DocumentEnricher.stableId(source, 0));
        assertThat(documents.get(0).getMetadata())
                .containsEntry("spiritName", "喵喵")
                .containsEntry("elementType", "草")
                .containsEntry("docType", "spirit");
    }

    @Test
    @DisplayName("dryRun 只解析不写库：有文档数，写入数为 0，两个仓库都不该被碰")
    void dryRunShouldNotWriteAnything() {
        IngestResult result = newService().ingest("all", true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.documentsParsed()).isPositive();
        assertThat(result.chunksCreated()).isPositive();
        assertThat(result.chunksWritten()).isZero();
        assertThat(result.spiritsWritten()).isZero();
        assertThat(result.skippedSpirits()).isEqualTo(1);
        assertThat(result.vectorStoreTotal()).isEqualTo(-1);
        assertThat(result.warnings()).isEmpty();

        verifyNoInteractions(vectorRepository, spiritRepository);
    }

    @Test
    @DisplayName("正式入库：结构化表与向量库都要写，且精灵数对得上")
    void ingestShouldWriteBothSides() {
        when(spiritRepository.replaceAll(anyList(), anyList()))
                .thenReturn(new SpiritRepository.StructuredCounts(1, 3, 4));
        when(spiritRepository.replaceElementTypes(anyList())).thenReturn(2);

        IngestResult result = newService().ingest("all", false);

        assertThat(result.chunksWritten()).isEqualTo(result.chunksCreated());
        assertThat(result.spiritsWritten()).isEqualTo(1);
        assertThat(result.elementTypesWritten()).isEqualTo(2);
        assertThat(result.vectorStoreTotal()).isZero();

        verify(spiritRepository).replaceAll(anyList(), anyList());
        verify(spiritRepository).replaceElementTypes(anyList());
        verify(vectorRepository, atLeastOnce()).save(anyList());
    }

    @Test
    @DisplayName("只跑 moves 时不该动结构化表（否则会把 spirit 表清空）")
    void movesOnlyShouldNotTouchStructuredTables() {
        IngestResult result = newService().ingest("moves", false);

        assertThat(result.chunksWritten()).isEqualTo(2);
        verifyNoInteractions(spiritRepository);
        verify(vectorRepository, atLeastOnce()).save(anyList());
    }

    @Test
    @DisplayName("数据字典只读 .md：同目录的 .json 配置表不能被当成语料")
    void wikiReaderShouldIgnoreJsonFiles() {
        WikiMarkdownReader.ReadResult read = new WikiMarkdownReader(dataRoot).readAll();

        assertThat(read.entries()).hasSize(1);
        assertThat(read.entries().get(0).source().getFileName().toString()).endsWith(".md");
        assertThat(read.warnings()).isEmpty();
    }

    @Test
    @DisplayName("参考数据小表要按各自的 docType 入库，不能被统一覆盖成一种")
    void referenceDocumentsShouldKeepTheirOwnDocType() {
        ReferenceDataReader.ReadResult read = new ReferenceDataReader(dataRoot).readAll();

        assertThat(read.elementTypes()).hasSize(2);
        List<String> docTypes = read.documents().stream()
                .map(document -> String.valueOf(document.getMetadata().get("docType")))
                .distinct()
                .sorted()
                .toList();
        assertThat(docTypes).containsExactly("element-type", "magic-item", "personality", "term");
    }

    @Test
    @DisplayName("未知 source 值直接报错，避免静默什么都不做")
    void unknownSourceShouldFail() {
        assertThatThrownBy(() -> newService().ingest("nonsense", true))
                .isInstanceOf(com.locke.ai.exception.IngestException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    @DisplayName("语料根目录不存在时，报错要说清是 data-root 配错了，而不是某个文件找不到")
    void missingDirectoryShouldFail() {
        DataRoot missing = new DataRoot(tempDir.resolve("not-exist").toString());
        IngestService service = new IngestService(
                new JsonDataReader(missing, true),
                new MoveDataReader(missing),
                new ItemDataReader(missing),
                new ReferenceDataReader(missing),
                new WikiMarkdownReader(missing),
                new SpiritChunkSplitter(25, true),
                new DocumentEnricher(),
                TokenTextSplitter.builder().withChunkSize(100).withMinChunkSizeChars(25).build(),
                vectorRepository,
                spiritRepository,
                missing,
                50);

        assertThatThrownBy(() -> service.ingest("pets", true))
                .isInstanceOf(com.locke.ai.exception.IngestException.class)
                .hasMessageContaining("语料根目录不存在");
        // 失败发生在写任何东西之前
        verify(spiritRepository, never()).replaceAll(anyList(), anyList());
        verify(spiritRepository, never()).replaceElementTypes(anyList());
    }

    @Test
    @DisplayName("缺 moves.json 时 pets 分支要能继续（技能总表只是喂 skill 表的附加数据）")
    void missingMovesFileShouldNotBreakPetsBranch() throws IOException {
        Files.delete(tempDir.resolve("moves.json"));

        IngestResult result = newService().ingest("pets", true);

        assertThat(result.documentsParsed()).isEqualTo(1);
        assertThat(result.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("技能总表"));

        // 但明确只跑 moves 时，缺文件就是错误
        assertThatThrownBy(() -> newService().ingest("moves", true))
                .isInstanceOf(com.locke.ai.exception.IngestException.class);
    }
}
