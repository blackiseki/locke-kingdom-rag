package com.locke.ai.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.locke.ai.util.LocalizedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 技能（游戏里叫 move）。
 *
 * <p>同一个结构出现在三处，字段完全一致，所以复用这一个 record：
 * <ul>
 *   <li>{@code pets/{id}.json} 的 {@code move_pool}——升级技能（主）</li>
 *   <li>{@code pets/{id}.json} 的 {@code move_stones}——技能石</li>
 *   <li>{@code pets/{id}.json} 的 {@code legacy_moves[].move}——血脉技能</li>
 * </ul>
 *
 * <p><b>不要拿 {@code moves.json} 的记录来 bind 这个类型</b>：那份数据的 id 空间
 * 和精灵内嵌的技能 id <b>完全没有交集</b>（1~3643 vs 7 位数），
 * 而且 {@code moves.json} 没有 {@code icon_id} 字段、{@code move_type} 可能为 null。
 * 两者用途不同：一份是技能总表，一份是精灵身上的技能。
 *
 * @param power 威力。<b>可以为 null</b>——变化类技能没有威力，实测 47% 的记录为 null，
 *              所以这里必须是 {@link Integer} 而不是 {@code int}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Skill(
        Long id,
        String name,
        String iconId,
        TypeRef moveType,
        Map<String, Object> localized,
        String moveCategory,
        Integer energyCost,
        Integer power,
        String description) {

    /** 中文技能名。 */
    public String displayName() {
        return LocalizedText.name(localized, name);
    }

    /** 中文技能效果描述。 */
    public String effect() {
        return LocalizedText.description(localized, description);
    }

    /**
     * 技能类别。
     *
     * <p>数据里是英文枚举（{@code Physical Attack} / {@code Magic Attack} / {@code Status}），
     * 这里只是把它译成中文，遇到没见过的值原样返回，不猜。
     */
    public String categoryLabel() {
        if (moveCategory == null) {
            return "未知";
        }
        return switch (moveCategory) {
            case "Physical Attack" -> "物理攻击";
            case "Magic Attack" -> "魔法攻击";
            case "Status" -> "变化";
            default -> moveCategory;
        };
    }

    public String typeLabel() {
        return moveType == null ? "无属性" : moveType.label();
    }

    /** 渲染成一行，供精灵档案使用。 */
    public String toArchiveLine() {
        List<String> parts = new ArrayList<>(4);
        parts.add(typeLabel());
        parts.add(categoryLabel());
        if (power != null) {
            parts.add("威力 " + power);
        }
        if (energyCost != null) {
            parts.add("能耗 " + energyCost);
        }
        StringBuilder sb = new StringBuilder("- ").append(displayName())
                .append('（').append(String.join(" / ", parts)).append('）');
        String text = effect();
        if (text != null && !text.isBlank()) {
            sb.append('：').append(text.trim());
        }
        return sb.toString();
    }

    /** 只要名字，用于「技能石 / 血脉技能」这类只需要召回、不需要解释的清单。 */
    public String toNameLine() {
        return "- " + displayName() + "（" + typeLabel() + (power == null ? "" : " 威力 " + power) + "）";
    }
}
