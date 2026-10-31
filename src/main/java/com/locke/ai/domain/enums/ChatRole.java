package com.locke.ai.domain.enums;

/**
 * 会话消息的角色。
 *
 * <p>只保留模型真正需要的两种：{@code system} 不落库——系统提示词是每次请求
 * 现渲染的（还要拼摘要），存进历史里会变成一份过期副本，改了提示词就对不上。
 */
public enum ChatRole {

    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    ChatRole(String value) {
        this.value = value;
    }

    /** 落库与发给模型时使用的字面量，与 OpenAI 协议的 role 一致。 */
    public String value() {
        return value;
    }

    /**
     * 反解数据库里的字符串。
     *
     * <p>读历史时遇到不认识的 role 不应该让整轮问答挂掉，
     * 所以这里返回 null 由调用方跳过该条。
     */
    public static ChatRole from(String raw) {
        if (raw == null) {
            return null;
        }
        for (ChatRole role : values()) {
            if (role.value.equalsIgnoreCase(raw.trim())) {
                return role;
            }
        }
        return null;
    }
}
