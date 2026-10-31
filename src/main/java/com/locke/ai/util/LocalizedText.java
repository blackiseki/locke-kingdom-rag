package com.locke.ai.util;

import java.util.Map;

/**
 * 处理 {@code localized.zh} 的三种不同形状。
 *
 * <p>真实数据里同一个字段名有三副面孔，这是本次接入最大的坑：
 * <ul>
 *   <li>精灵、技能：{@code "localized": {"zh": {"name": "...", "description": "..."}}}（对象）</li>
 *   <li>属性、血脉、技能类型：{@code "localized": {"zh": "地"}}（字符串）</li>
 *   <li>道具、道具表：没有 {@code localized}，中文直接写在 {@code description} 里</li>
 * </ul>
 * 用一个 POJO 去 bind {@code zh} 必然有一边炸掉，所以统一按
 * {@code Map<String, Object>} 接住，再由这里取字段。
 */
public final class LocalizedText {

    private LocalizedText() {
    }

    /**
     * 取中文名。
     *
     * @param fallback 拿不到中文时的兜底（一般是英文 {@code name} 字段）
     */
    public static String name(Map<String, Object> localized, String fallback) {
        Object zh = zh(localized);
        if (zh instanceof Map<?, ?> map) {
            String value = text(map.get("name"));
            if (value != null) {
                return value;
            }
        }
        if (zh instanceof String value && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    /**
     * 取中文描述。
     *
     * @param fallback 拿不到中文时的兜底（一般是英文 {@code description}）
     */
    public static String description(Map<String, Object> localized, String fallback) {
        Object zh = zh(localized);
        if (zh instanceof Map<?, ?> map) {
            String value = text(map.get("description"));
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private static Object zh(Map<String, Object> localized) {
        return localized == null ? null : localized.get("zh");
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
