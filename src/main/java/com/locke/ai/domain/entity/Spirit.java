package com.locke.ai.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.locke.ai.domain.enums.ElementType;
import com.locke.ai.util.LocalizedText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 精灵。
 *
 * <p>字段直接对齐 {@code public/data/pets/{id}.json}——这份文件是<b>自包含的完整记录</b>，
 * 包含了 {@code Pets.json} 的全部字段（少一个 {@code species_id}，可由 {@code species.id} 得到），
 * 所以入库只需要读 {@code pets/*.json} 这一份，不用再读 {@code Pets.json}。
 *
 * <p>id 语义（来自数据生成脚本 {@code sync-rocom-data} 的确认）：
 * <ul>
 *   <li>{@code id} = PETBASE 主键，也就是文件名；正典精灵在 3000~3999，其余是首领/异形态</li>
 *   <li>{@code speciesId} = 图鉴/手册 id，用来把同一个进化家族的不同形态归到一组</li>
 *   <li>{@code evolutionTree.currentMonsterId} = 当前这份文件自己的 id</li>
 * </ul>
 *
 * <p><b>可空字段很多，别当基本类型用</b>（实测于 1065 条记录）：
 * {@code subType} 633 条为 null、{@code evolvesFromId} 595 条为 null、
 * {@code trait} 197 条为 null、{@code catchInfo} 350 条为 null。
 * 另外 {@code movePool} 有 242 条是空数组——已实装的 613 只里也有 1 只
 * （{@code 4076.json} 伊里斯）完全没有技能，渲染档案时要能容忍。
 *
 * <p>没有收录的字段：{@code breeding}/{@code breedingProfile}（配种类需求，
 * 属于另一条业务线）、{@code moveStones} 之外的 {@code leaderPotential} 等。
 * 需要时按同样的方式加进来即可，mapper 开了忽略未知字段，加字段不会影响现有解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Spirit(
        Long id,
        Integer speciesId,
        String name,
        String form,
        TypeRef mainType,
        TypeRef subType,
        TypeRef defaultLegacyType,
        Boolean leaderPotential,
        Boolean isLeaderForm,
        String preferredAttackStyle,
        Map<String, Object> localized,
        Boolean implemented,
        Integer baseHp,
        Integer basePhyAtk,
        Integer baseMagAtk,
        Integer basePhyDef,
        Integer baseMagDef,
        Integer baseSpd,
        Long evolvesFromId,
        TypeRef species,
        TraitRef trait,
        List<Skill> movePool,
        List<Skill> moveStones,
        List<LegacyMove> legacyMoves,
        EvolutionTree evolutionTree,
        WorldProfile worldProfile,
        CatchInfo catchInfo) {

    /** 进化条件里的等级，例如 {@code "等级达到 30 级"} -> 30。 */
    private static final Pattern EVOLVE_LEVEL = Pattern.compile("等级达到\\s*([0-9,]+)\\s*级");

    public Spirit {
        movePool = movePool == null ? List.of() : List.copyOf(movePool);
        moveStones = moveStones == null ? List.of() : List.copyOf(moveStones);
        legacyMoves = legacyMoves == null ? List.of() : List.copyOf(legacyMoves);
    }

    /**
     * 由枚举反推一个属性引用。
     *
     * <p>从数据库读回来时只有 {@code main_type} 那一列（枚举名），
     * 用它合成 {@link TypeRef}，这样 {@link #displayName()}、{@link #mainTypeLabel()}
     * 这些方法在读库和读文件两条路径上行为一致。
     */
    public static TypeRef typeRefOf(ElementType element) {
        if (element == null || element == ElementType.UNKNOWN) {
            return null;
        }
        return new TypeRef(element.id(), element.name(), Map.of("zh", element.label()));
    }

    /** 把中文名包成 {@code localized} 的形状，让读库路径也能走同一套取值逻辑。 */
    public static Map<String, Object> localizedOf(String zhName) {
        return (zhName == null || zhName.isBlank()) ? null : Map.of("zh", Map.of("name", zhName));
    }
    /**
     * 血脉技能：{@code legacy_moves} 的元素并不是技能本身，而是一个包装对象
     * {@code {monster_id, type_id, move_id, move}}，真正的技能在 {@code move} 字段里。
     *
     * <p>这个坑我是在第一次全量入库后才发现的——当时把外层直接当成 {@link Skill} 绑定，
     * 结果所有字段都是 null，渲染出 18 行「null」，连模型都在答案里指出
     * 「血脉技能 18 项均显示为 null，无有效内容」。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LegacyMove(Integer monsterId, Long moveId, Integer typeId, Skill move) {
    }

    /** 取出血脉技能里真正的技能对象；外层包装字段（monsterId/typeId）对渲染没有用。 */
    public List<Skill> legacyMoveSkills() {
        return legacyMoves.stream().map(LegacyMove::move).filter(Objects::nonNull).toList();
    }

    /** 中文名。实测已实装的 613 只全都有，取不到才退回形态名。 */
    public String displayName() {
        return LocalizedText.name(localized, form);
    }

    /** 图鉴/手册 id，取不到就用自身 id。 */
    public int groupId() {
        if (speciesId != null) {
            return speciesId;
        }
        return species != null && species.id() != null ? species.id() : (id == null ? -1 : id.intValue());
    }

    public String mainTypeLabel() {
        return mainType == null ? "未知" : mainType.label();
    }

    public ElementType mainElement() {
        return mainType == null ? ElementType.UNKNOWN : mainType.element();
    }

    public ElementType subElement() {
        return subType == null ? null : subType.element();
    }

    /** 六项种族值之和，用于「这只精灵强不强」的粗比较。缺项按 0 计。 */
    public int baseStatTotal() {
        return nz(baseHp) + nz(basePhyAtk) + nz(baseMagAtk) + nz(basePhyDef) + nz(baseMagDef) + nz(baseSpd);
    }

    /**
     * 这只精灵自身的进化等级（要进化到它需要多少级）。
     *
     * <p>数据里没有结构化的等级字段，只有进化条件里那句中文
     * （{@code "等级达到 30 级"}），所以这里从当前形态所在阶段的
     * {@code evolution_conditions} 里正则提取。
     *
     * @return 解析不到时返回 null（可能是道具进化、羁绊进化或本身就是初始形态）
     */
    public Integer evolveLevel() {
        if (evolutionTree == null || evolutionTree.stages() == null) {
            return null;
        }
        long currentId = evolutionTree.currentMonsterId() != null
                ? evolutionTree.currentMonsterId()
                : (id == null ? -1L : id);
        for (EvolutionStage stage : evolutionTree.stages()) {
            if (stage.monsters() == null) {
                continue;
            }
            for (EvolutionMonster monster : stage.monsters()) {
                if (Objects.equals(monster.id(), currentId)) {
                    return parseLevel(monster.evolutionConditions());
                }
            }
        }
        return null;
    }

    /**
     * 渲染进化链，每个阶段一行。
     *
     * <p>同一个阶段可能有多个分支形态（实测 391 只精灵的某个阶段有 2~9 个），
     * 所以这里并列展示而不是拉成一条线。条件是「进入该阶段」的条件，
     * 挂在目标形态上——同一阶段多个分支条件不同时这里会合并，属于已知的简化。
     */
    public List<String> evolutionLines() {
        if (evolutionTree == null || evolutionTree.stages() == null || evolutionTree.stages().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (EvolutionStage stage : evolutionTree.stages()) {
            List<EvolutionMonster> monsters = stage.monsters() == null ? List.of() : stage.monsters();
            if (monsters.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            List<String> conditions = new ArrayList<>();
            for (EvolutionMonster monster : monsters) {
                names.add(monster.displayName());
                if (monster.evolutionConditions() != null) {
                    for (String condition : monster.evolutionConditions()) {
                        if (!conditions.contains(condition)) {
                            conditions.add(condition);
                        }
                    }
                }
            }
            StringBuilder line = new StringBuilder("- 第")
                    .append(stage.depth() == null ? "?" : stage.depth())
                    .append("阶段");
            if (Boolean.TRUE.equals(stage.isLeaderStage())) {
                line.append("（首领形态）");
            }
            line.append('：').append(String.join(" / ", names));
            if (!conditions.isEmpty()) {
                line.append(" ← ").append(String.join("；", conditions));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    static Integer parseLevel(List<String> conditions) {
        if (conditions == null) {
            return null;
        }
        for (String condition : conditions) {
            Matcher matcher = EVOLVE_LEVEL.matcher(condition);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                    // 数据里偶尔出现 "等级达到 １６ 级" 这类全角数字，跳过即可
                }
            }
        }
        return null;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 特性（{@code trait}），例如「圣光」。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TraitRef(
            Integer id,
            String name,
            String description,
            Map<String, Object> localized) {

        public String label() {
            return LocalizedText.name(localized, name);
        }

        public String effect() {
            return LocalizedText.description(localized, description);
        }
    }

    /**
     * 进化树。
     *
     * @param stages               按 depth 升序的阶段列表
     * @param maxDepth             最大阶段序号，等于 {@code stages.size() - 1}
     * @param totalUniqueMonsters  家族里不同形态的数量
     * @param speciesId            家族分组 id
     * @param currentMonsterId     当前这份文件自己的精灵 id
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvolutionTree(
            List<EvolutionStage> stages,
            Integer maxDepth,
            Integer totalUniqueMonsters,
            Long speciesId,
            Long currentMonsterId) {

        public EvolutionTree {
            stages = stages == null ? List.of() : List.copyOf(stages);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvolutionStage(
            Integer depth,
            List<EvolutionMonster> monsters,
            Boolean isLeaderStage) {

        public EvolutionStage {
            monsters = monsters == null ? List.of() : List.copyOf(monsters);
        }
    }

    /**
     * 进化链上的一个形态。
     *
     * @param evolutionConditions 已经是渲染好的中文短句数组，例如
     *                            {@code ["等级达到 30 级", "消耗 12,000 洛克贝"]}。
     *                            注意它描述的是<b>如何进入这个形态</b>，挂在目标形态上。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvolutionMonster(
            Long id,
            Integer speciesId,
            String name,
            String form,
            Map<String, Object> localized,
            Boolean isLeaderForm,
            TypeRef mainType,
            TypeRef subType,
            List<String> evolutionConditions) {

        public EvolutionMonster {
            evolutionConditions = evolutionConditions == null ? List.of() : List.copyOf(evolutionConditions);
        }

        public String displayName() {
            return LocalizedText.name(localized, form);
        }
    }

    /**
     * 图鉴/世界资料。
     *
     * <p>字段全部存在，但内容可能为 null：{@code introduction} 886 条有值（平均仅 67 字），
     * {@code typeDesc} / {@code descriptionHabitat} 各 523 条，{@code refreshLocations} 850 条非空。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorldProfile(
            String typeDesc,
            String descriptionHabitat,
            String introduction,
            List<String> refreshLocations,
            String movementType,
            Integer classisId,
            String classisName,
            List<Integer> handbookAreaIds) {

        public WorldProfile {
            refreshLocations = refreshLocations == null ? List.of() : List.copyOf(refreshLocations);
            handbookAreaIds = handbookAreaIds == null ? List.of() : List.copyOf(handbookAreaIds);
        }
    }

    /**
     * 捕捉参数。
     *
     * <p>715 条非 null，但其中 185 条三个子字段全为 null。这三个数字的确切含义
     * 数据源里没有说明，所以<b>只写库、不写进向量档案</b>——
     * 宁可答不出来，也不要拿含义不明的数值编一个说法给用户。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatchInfo(
            Integer catchThreshold,
            Integer catchGuarantRate,
            Integer catchBallLevel) {

        public boolean isEmpty() {
            return catchThreshold == null && catchGuarantRate == null && catchBallLevel == null;
        }
    }
}
