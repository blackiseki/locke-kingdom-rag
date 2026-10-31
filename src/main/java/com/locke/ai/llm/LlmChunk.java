package com.locke.ai.llm;

/**
 * 流式响应中的一个增量块。
 *
 * @param delta        增量文本；纯 usage 块时为空串
 * @param usage        服务端返回的 token 用量；通常只在最后一个数据块出现
 * @param finishReason 结束原因，可能为 null
 */
public record LlmChunk(String delta, LlmUsage usage, String finishReason) {

    public static LlmChunk text(String delta) {
        return new LlmChunk(delta, null, null);
    }

    public static LlmChunk usage(LlmUsage usage) {
        return new LlmChunk("", usage, null);
    }
}
