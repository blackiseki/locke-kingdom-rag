package com.locke.ai.llm;

/**
 * 发给模型的一条对话消息。
 *
 * <p>只有 {@code system / user / assistant} 三种角色有意义，这里不做枚举：
 * role 的取值必须和 OpenAI 协议完全一致，用字符串反而少一层转换，
 * 也让「以后要加 tool 角色」不用改这里。
 *
 * @param role    user / assistant
 * @param content 正文
 */
public record LlmMessage(String role, String content) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static LlmMessage user(String content) {
        return new LlmMessage(ROLE_USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(ROLE_ASSISTANT, content);
    }
}
