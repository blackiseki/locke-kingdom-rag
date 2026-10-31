package com.locke.ai.repository;

import com.locke.ai.domain.dto.TypeRecord;
import com.locke.ai.domain.entity.Skill;
import com.locke.ai.domain.entity.Spirit;
import com.locke.ai.domain.enums.ElementType;
import com.locke.ai.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 结构化数据访问：精灵 / 技能 / 属性克制表。
 *
 * <p>走 JdbcTemplate 而不是 JPA：这里是「读一堆 JSON → 批量灌库」的单向管道，
 * 不需要实体关系管理，也避免 JPA 与 pgvector 类型映射互相干扰。
 *
 * <h3>重建语义</h3>
 * {@link #replaceAll} 是<b>全量重建</b>：先清空 {@code spirit_skill / skill / spirit}
 * 再批量写入。语料本身就是全量导出的游戏数据，做增量 diff 没有意义，
 * 而且能避开主键冲突。代价是<b>调用方必须一次性传入完整的精灵集合</b>——
 * 如果你只想更新某几只精灵，不要用这个方法。
 *
 * <h3>从库里读回来的 Spirit 是「不完整」的</h3>
 * 表里只存了问答需要的字段，{@code localized}、{@code evolutionTree}、
 * {@code worldProfile} 这些嵌套结构没有落库。所以读回来的对象
 * 只保证 {@code displayName()}、{@code mainTypeLabel()}、种族值这些能用，
 * 需要完整对象时请重新解析原始 JSON。
 */
@Repository
public class SpiritRepository {

    private final JdbcTemplate jdbc;

    public SpiritRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 重建结果的计数，用于 {@code IngestResult}。 */
    public record StructuredCounts(int spirits, int skills, int links) {
    }

    /**
     * 全量重建精灵与技能数据。
     *
     * @param spirits       精灵集合（调用方负责过滤 {@code implemented}）
     * @param catalogSkills {@code moves.json} 的技能总表，可为空
     */
    @Transactional
    public StructuredCounts replaceAll(List<Spirit> spirits, List<Skill> catalogSkills) {
        // 先删关联再删主表，不依赖级联，语义更直白
        jdbc.update("DELETE FROM spirit_skill");
        jdbc.update("DELETE FROM skill");
        jdbc.update("DELETE FROM spirit");

        int spiritCount = insertSpirits(spirits);

        // 同一 id 可能同时出现在 moves.json 和精灵内嵌技能里，
        // 用 LinkedHashMap 去重；后写入的覆盖先写入的，所以先放 catalog 再放精灵内嵌的
        Map<Long, Skill> skillsById = new LinkedHashMap<>();
        if (catalogSkills != null) {
            for (Skill skill : catalogSkills) {
                if (skill.id() != null) {
                    skillsById.put(skill.id(), skill);
                }
            }
        }
        List<Object[]> links = new ArrayList<>();
        for (Spirit spirit : spirits) {
            if (spirit.id() == null) {
                continue;
            }
            collect(spirit, spirit.movePool(), "level_up", skillsById, links);
            collect(spirit, spirit.moveStones(), "stone", skillsById, links);
            collect(spirit, spirit.legacyMoveSkills(), "legacy", skillsById, links);
        }

        int skillCount = insertSkills(skillsById.values());
        int linkCount = insertLinks(links);
        return new StructuredCounts(spiritCount, skillCount, linkCount);
    }

    private void collect(Spirit spirit, List<Skill> skills, String source,
                         Map<Long, Skill> skillsById, List<Object[]> links) {
        for (Skill skill : skills) {
            if (skill.id() == null) {
                continue;
            }
            skillsById.putIfAbsent(skill.id(), skill);
            links.add(new Object[]{spirit.id(), skill.id(), source});
        }
    }

    private int insertSpirits(List<Spirit> spirits) {
        if (spirits.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO spirit (id, species_id, name, display_name, form, main_type, sub_type,
                                    legacy_type, trait_name, trait_effect, evolved_from_id, evolve_level,
                                    base_hp, base_phy_atk, base_mag_atk, base_phy_def, base_mag_def, base_spd,
                                    base_stat_total, implemented, is_leader_form, preferred_attack_style,
                                    classis_name, introduction, habitat, type_desc, refresh_locations,
                                    catch_threshold, catch_guarant_rate, catch_ball_level, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """;
        List<Object[]> batch = new ArrayList<>(spirits.size());
        for (Spirit s : spirits) {
            Spirit.WorldProfile wp = s.worldProfile();
            Spirit.CatchInfo ci = s.catchInfo();
            batch.add(new Object[]{
                    s.id(),
                    s.speciesId(),
                    s.name(),
                    s.displayName(),
                    s.form(),
                    enumName(s.mainElement()),
                    s.subElement() == null ? null : enumName(s.subElement()),
                    s.defaultLegacyType() == null ? null : enumName(s.defaultLegacyType().element()),
                    s.trait() == null ? null : s.trait().label(),
                    s.trait() == null ? null : s.trait().effect(),
                    s.evolvesFromId(),
                    s.evolveLevel(),
                    s.baseHp(), s.basePhyAtk(), s.baseMagAtk(), s.basePhyDef(), s.baseMagDef(), s.baseSpd(),
                    s.baseStatTotal(),
                    Boolean.TRUE.equals(s.implemented()),
                    Boolean.TRUE.equals(s.isLeaderForm()),
                    s.preferredAttackStyle(),
                    wp == null ? null : wp.classisName(),
                    wp == null ? null : wp.introduction(),
                    wp == null ? null : wp.descriptionHabitat(),
                    wp == null ? null : wp.typeDesc(),
                    wp == null ? null : String.join("\n", wp.refreshLocations()),
                    ci == null ? null : ci.catchThreshold(),
                    ci == null ? null : ci.catchGuarantRate(),
                    ci == null ? null : ci.catchBallLevel(),
                    JsonUtils.toJson(s)
            });
        }
        jdbc.batchUpdate(sql, batch);
        return batch.size();
    }

    private int insertSkills(Collection<Skill> skills) {
        if (skills.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO skill (id, name, display_name, move_type, move_category, energy_cost, power, effect, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    display_name  = EXCLUDED.display_name,
                    move_type     = EXCLUDED.move_type,
                    move_category = EXCLUDED.move_category,
                    energy_cost   = EXCLUDED.energy_cost,
                    power         = EXCLUDED.power,
                    effect        = EXCLUDED.effect
                """;
        List<Object[]> batch = new ArrayList<>(skills.size());
        for (Skill skill : skills) {
            batch.add(new Object[]{
                    skill.id(),
                    skill.name(),
                    skill.displayName(),
                    skill.moveType() == null ? null : enumName(skill.moveType().element()),
                    skill.moveCategory(),
                    skill.energyCost(),
                    skill.power(),
                    skill.effect(),
                    // 7 位 id 是精灵身上的技能，短 id 来自 moves.json 总表
                    skill.id() >= 1_000_000 ? "pet" : "catalog"
            });
        }
        jdbc.batchUpdate(sql, batch);
        return batch.size();
    }

    private int insertLinks(List<Object[]> links) {
        if (links.isEmpty()) {
            return 0;
        }
        // 同一只精灵的同一个技能可能同时出现在不同来源里，主键是三元组，冲突就忽略
        jdbc.batchUpdate("""
                INSERT INTO spirit_skill (spirit_id, skill_id, source) VALUES (?, ?, ?)
                ON CONFLICT (spirit_id, skill_id, source) DO NOTHING
                """, links);
        return links.size();
    }

    /** 重建属性克制表（来自 types.json，19 条）。 */
    @Transactional
    public int replaceElementTypes(List<TypeRecord> types) {
        jdbc.update("DELETE FROM element_type");
        if (types == null || types.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO element_type (id, name, label, vulnerable_to, resistant_to,
                                          vulnerable_labels, resistant_labels)
                VALUES (?, ?, ?, ?::text[], ?::text[], ?::text[], ?::text[])
                """;
        List<Object[]> batch = new ArrayList<>(types.size());
        for (TypeRecord type : types) {
            batch.add(new Object[]{
                    type.id(),
                    type.name(),
                    type.label(),
                    // ⚠ 这里的 ?::text[] 强制转换是必须的，不能省。
                    // Postgres 的 text[] 列不接受 varchar 参数，会报
                    // "column is of type text[] but expression is of type character varying"。
                    // 这个错误是我在第一次全量入库时才踩到的：element_type 表插不进去，
                    // 异常直接把整批入库中断了（reference 和 wiki 的向量都没写进去）。
                    //
                    // 不用 java.sql.Array/createArrayOf 是因为它需要 Connection，
                    // 走 JdbcTemplate 得套 ConnectionCallback，比这里写个字面量字符串啰嗦得多。
                    toPgArray(type.vulnerableTo()),
                    toPgArray(type.resistantTo()),
                    toPgArray(TypeRecord.toLabels(type.vulnerableTo())),
                    toPgArray(TypeRecord.toLabels(type.resistantTo()))
            });
        }
        jdbc.batchUpdate(sql, batch);
        return batch.size();
    }

    /** 把字符串列表转成 Postgres 数组字面量，例如 {@code {Flying,Fire}}。 */
    private static String toPgArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            // 元素里的双引号和反斜杠要转义，中文不需要
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    // ============================================================
    // 查询
    // ============================================================

    /** 精确按中文名查一只精灵（含技能）。 */
    public Optional<Spirit> findByDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Optional.empty();
        }
        List<Spirit> found = jdbc.query(SELECT_SPIRIT + " WHERE display_name = ?",
                SPIRIT_MAPPER, displayName.trim());
        return found.stream().findFirst().map(this::withSkills);
    }

    /**
     * 按中文名模糊搜索。走 {@code idx_spirit_display_name_trgm} 的 GIN 索引。
     *
     * <p>结果都带上技能：这条路是 {@code RetrieverService} 的兜底路径，
     * 返回的文档要给模型看，缺技能就答不了「它有什么技能」。
     * limit 已被上层夹到 100 以内，所以 N+1 查询可接受。
     */
    public List<Spirit> searchByDisplayName(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return jdbc.query(SELECT_SPIRIT + " WHERE display_name ILIKE ? ORDER BY display_name LIMIT ?",
                        SPIRIT_MAPPER, "%" + keyword.trim() + "%", limit)
                .stream()
                .map(this::withSkills)
                .toList();
    }

    /** 按属性（主属性或副属性）查精灵，种族值高的排前面。 */
    public List<Spirit> findByElement(ElementType element, int limit) {
        if (element == null || element == ElementType.UNKNOWN) {
            return List.of();
        }
        return jdbc.query(SELECT_SPIRIT
                                + " WHERE main_type = ? OR sub_type = ? ORDER BY base_stat_total DESC NULLS LAST LIMIT ?",
                        SPIRIT_MAPPER, element.name(), element.name(), limit)
                .stream()
                .map(this::withSkills)
                .toList();
    }

    /** 查某只精灵的技能，按来源排序。 */
    public List<Skill> findSkills(long spiritId) {
        return jdbc.query("""
                SELECT s.id, s.name, s.display_name, s.move_type, s.move_category,
                       s.energy_cost, s.power, s.effect, ss.source
                FROM spirit_skill ss
                JOIN skill s ON s.id = ss.skill_id
                WHERE ss.spirit_id = ?
                ORDER BY ss.source, s.display_name
                """, SKILL_MAPPER, spiritId);
    }

    public int countSpirits() {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM spirit", Integer.class);
        return total == null ? 0 : total;
    }

    public int countSkills() {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM skill", Integer.class);
        return total == null ? 0 : total;
    }

    private Spirit withSkills(Spirit spirit) {
        if (spirit.id() == null) {
            return spirit;
        }
        List<Skill> skills = findSkills(spirit.id());
        return new Spirit(spirit.id(), spirit.speciesId(), spirit.name(), spirit.form(),
                spirit.mainType(), spirit.subType(), spirit.defaultLegacyType(),
                spirit.leaderPotential(), spirit.isLeaderForm(), spirit.preferredAttackStyle(),
                spirit.localized(), spirit.implemented(),
                spirit.baseHp(), spirit.basePhyAtk(), spirit.baseMagAtk(),
                spirit.basePhyDef(), spirit.baseMagDef(), spirit.baseSpd(),
                spirit.evolvesFromId(), spirit.species(), spirit.trait(),
                skills, List.of(), List.of(),
                spirit.evolutionTree(), spirit.worldProfile(), spirit.catchInfo());
    }

    private static String enumName(ElementType element) {
        return (element == null || element == ElementType.UNKNOWN) ? null : element.name();
    }

    private static final String SELECT_SPIRIT = """
            SELECT id, species_id, name, display_name, form, main_type, sub_type, legacy_type,
                   trait_name, trait_effect, evolved_from_id, evolve_level,
                   base_hp, base_phy_atk, base_mag_atk, base_phy_def, base_mag_def, base_spd,
                   base_stat_total, implemented, is_leader_form, preferred_attack_style,
                   classis_name, introduction, habitat, type_desc, refresh_locations,
                   catch_threshold, catch_guarant_rate, catch_ball_level
            FROM spirit
            """;

    /**
     * 把数据库行还原成 {@link Spirit}。
     *
     * <p>没落库的字段（localized 之外的嵌套结构）一律传 null，
     * 属性用 {@link Spirit#typeRefOf} 由枚举合成，这样取值方法和读文件路径一致。
     */
    private static final org.springframework.jdbc.core.RowMapper<Spirit> SPIRIT_MAPPER = (rs, rowNum) -> {
        ElementType main = ElementType.fromJson(rs.getString("main_type"));
        ElementType sub = rs.getString("sub_type") == null ? null : ElementType.fromJson(rs.getString("sub_type"));
        return new Spirit(
                rs.getLong("id"),
                (Integer) rs.getObject("species_id"),
                rs.getString("name"),
                rs.getString("form"),
                Spirit.typeRefOf(main),
                Spirit.typeRefOf(sub),
                null,                                   // defaultLegacyType 未落库
                null,                                   // leaderPotential 未落库
                rs.getBoolean("is_leader_form"),
                rs.getString("preferred_attack_style"),
                Spirit.localizedOf(rs.getString("display_name")),
                rs.getBoolean("implemented"),
                (Integer) rs.getObject("base_hp"),
                (Integer) rs.getObject("base_phy_atk"),
                (Integer) rs.getObject("base_mag_atk"),
                (Integer) rs.getObject("base_phy_def"),
                (Integer) rs.getObject("base_mag_def"),
                (Integer) rs.getObject("base_spd"),
                (Long) rs.getObject("evolved_from_id"),
                null,                                   // species 未落库
                // 特性：表里拆成 name/effect 两列，这里合成原本的嵌套结构
                rs.getString("trait_name") == null ? null
                        : new Spirit.TraitRef(null, rs.getString("trait_name"), rs.getString("trait_effect"), null),
                List.of(),                              // 技能由 withSkills 单独查
                List.of(),
                List.of(),
                null,                                   // evolutionTree 未落库
                // 图鉴资料：只存了其中几个字段，其余留空
                new Spirit.WorldProfile(
                        rs.getString("type_desc"),
                        rs.getString("habitat"),
                        rs.getString("introduction"),
                        splitLines(rs.getString("refresh_locations")),
                        null,
                        null,
                        rs.getString("classis_name"),
                        List.of()),
                new Spirit.CatchInfo(
                        (Integer) rs.getObject("catch_threshold"),
                        (Integer) rs.getObject("catch_guarant_rate"),
                        (Integer) rs.getObject("catch_ball_level")));
    };

    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("\n"));
    }

    private static final org.springframework.jdbc.core.RowMapper<Skill> SKILL_MAPPER = (rs, rowNum) -> {
        ElementType type = ElementType.fromJson(rs.getString("move_type"));
        return new Skill(
                rs.getLong("id"),
                rs.getString("name"),
                null,
                Spirit.typeRefOf(type),
                Spirit.localizedOf(rs.getString("display_name")),
                rs.getString("move_category"),
                (Integer) rs.getObject("energy_cost"),
                (Integer) rs.getObject("power"),
                rs.getString("effect"));
    };
}
