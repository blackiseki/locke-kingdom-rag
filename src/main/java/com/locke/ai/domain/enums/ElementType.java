package com.locke.ai.domain.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * 属性（元素类型）。
 *
 * <p><b>这 19 项是照着 {@code public/data/types.json} 抄的</b>，不是猜的。
 * 之前骨架里我按记忆猜的版本（EARTH 土 / DEMON 恶魔 / WING 翼 / ROCK 石 / DIVINE 神）
 * 和真实数据对不上——真实数据里是 Ground 地、Dark 恶、Flying 翼，
 * 而且还有 Illusion 幻 和 Leader 首领，没有 Rock/Divine。
 *
 * <p>{@code id} 与 types.json 的 {@code id} 一一对应，可以直接用来 join。
 *
 * <p><b>克制关系不在这里</b>：{@code vulnerable_to} / {@code resistant_to} 是
 * 19×19 的二维关系，塞进枚举既写不下也没法维护，
 * 它们由 {@code types.json} 读进 {@code element_type} 表，
 * 查询时用 SQL 处理。
 */
public enum ElementType {

    NORMAL(1, "普通"),
    GRASS(2, "草"),
    FIRE(3, "火"),
    WATER(4, "水"),
    LIGHT(5, "光"),
    GROUND(6, "地"),
    ICE(7, "冰"),
    DRAGON(8, "龙"),
    ELECTRIC(9, "电"),
    POISON(10, "毒"),
    BUG(11, "虫"),
    FIGHTING(12, "武"),
    FLYING(13, "翼"),
    CUTE(14, "萌"),
    GHOST(15, "幽"),
    DARK(16, "恶"),
    MECHANICAL(17, "机械"),
    ILLUSION(18, "幻"),
    /** types.json 里 id=19，是「首领形态」占位，不是真正的属性。 */
    LEADER(19, "首领"),
    /** 数据缺失或出现新属性时的兜底，不要写进库。 */
    UNKNOWN(-1, "未知");

    private final int id;
    private final String label;

    ElementType(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public int id() {
        return id;
    }

    /** 中文显示名，用于 Prompt 和接口返回。 */
    public String label() {
        return label;
    }

    private static final Map<Integer, ElementType> BY_ID = new HashMap<>();

    static {
        for (ElementType type : values()) {
            BY_ID.put(type.id, type);
        }
    }

    /** 按 types.json 的 id 解析。 */
    public static ElementType fromId(Integer id) {
        if (id == null) {
            return UNKNOWN;
        }
        return BY_ID.getOrDefault(id, UNKNOWN);
    }

    /**
     * 宽松解析：接受枚举名（{@code "Ground"}，大小写不敏感）、
     * 中文名（{@code "地"}）以及 {@code "地系"} 这类带后缀的写法。
     *
     * @return 匹配不到时返回 {@link #UNKNOWN}
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static ElementType fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String value = raw.trim();
        for (ElementType type : values()) {
            if (type.name().equalsIgnoreCase(value)
                    || type.label.equals(value)
                    || (type.label + "系").equals(value)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
