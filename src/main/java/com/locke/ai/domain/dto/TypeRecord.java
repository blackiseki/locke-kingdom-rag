package com.locke.ai.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.locke.ai.util.LocalizedText;

import java.util.List;
import java.util.Map;

/**
 * {@code public/data/types.json} 的一条记录：属性及其克制关系。
 *
 * <p>这是 19 个属性的<b>克制表</b>（不是属性标签列表）。中文名在
 * {@code localized.zh} 里是<b>字符串</b>形式，例如 {@code {"zh": "地"}}。
 *
 * <p>克制关系是 19×19 的二维关系，不适合塞进枚举，所以读成一行行数据
 * 进 {@code element_type} 表，查询时用 SQL 处理。数组元素是英文属性名
 * （{@code "Flying"}、{@code "Fire"}），需要中文时在这里翻译成标签再返回。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TypeRecord(
        Integer id,
        String name,
        Map<String, Object> localized,
        List<String> vulnerableTo,
        List<String> resistantTo) {

    public TypeRecord {
        vulnerableTo = vulnerableTo == null ? List.of() : List.copyOf(vulnerableTo);
        resistantTo = resistantTo == null ? List.of() : List.copyOf(resistantTo);
    }

    /** 中文属性名。 */
    public String label() {
        return LocalizedText.name(localized, name);
    }

    /** 转换为 Postgres 的 {@code text[]} 字面量，供 JdbcTemplate 直接绑定。 */
    public static String[] toArray(List<String> values) {
        return values == null ? new String[0] : values.toArray(String[]::new);
    }

    /** 把英文属性名数组译成中文标签，例如 {@code ["Flying","Fire"] -> ["翼","火"]}。 */
    public static List<String> toLabels(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(com.locke.ai.domain.enums.ElementType::fromJson)
                .map(com.locke.ai.domain.enums.ElementType::label)
                .toList();
    }
}
