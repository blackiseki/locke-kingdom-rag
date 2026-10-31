package com.locke.ai.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.locke.ai.domain.enums.ElementType;
import com.locke.ai.util.LocalizedText;

import java.util.Map;

/**
 * 属性引用，形如 {@code {"id": 6, "name": "Ground", "localized": {"zh": "地"}}}。
 *
 * <p>精灵的主属性 / 副属性、技能属性、血脉类型、图鉴分组用的都是这个结构，
 * 所以抽成独立类型，别在每一处重复定义。
 *
 * <p>注意 {@code localized.zh} 在这里是<b>字符串</b>而不是对象——和精灵、技能
 * 上的 {@code localized} 形状不同，取值统一交给 {@link LocalizedText}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TypeRef(
        Integer id,
        String name,
        Map<String, Object> localized) {

    /** 中文名，拿不到就退回英文名（例如 {@code "Ground"}）。 */
    public String label() {
        return LocalizedText.name(localized, name);
    }

    /** 转成枚举，用于写库和 Prompt。 */
    public ElementType element() {
        return ElementType.fromJson(name);
    }

    @Override
    public String toString() {
        return label();
    }
}
