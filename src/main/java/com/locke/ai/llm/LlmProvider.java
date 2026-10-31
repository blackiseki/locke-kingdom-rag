package com.locke.ai.llm;

import java.util.stream.Stream;

/**
 * 单个大模型 Provider 的能力边界。
 *
 * <p>实现类负责协议细节（请求体、鉴权、SSE 解析、usage 解析），
 * 路由、降级、指标统计都在 {@link LlmRouter} 完成。
 */
public interface LlmProvider {

    /** 对外暴露的 provider id，例如 deepseek / tongyi。 */
    String id();

    /** 实际模型名，例如 deepseek-flash / qwen-plus。 */
    String model();

    /** ready / disabled / missing base-url / missing model / missing api-key。 */
    String status();

    /** 是否具备发起调用的最低配置。 */
    default boolean available() {
        return "ready".equals(status());
    }

    /**
     * 非流式调用，必须等完整响应后返回。
     *
     * @throws com.locke.ai.exception.LlmException 网络、鉴权、限流、解析失败
     */
    LlmCompletion complete(LlmRequest request);

    /**
     * 流式调用。
     *
     * <p>返回的 Stream 必须可关闭：调用方取消/异常退出时会 close，
     * 从而关闭底层 HTTP 连接。Stream 中抛出的异常由路由层捕获并决定
     * 是否切换下一个 Provider。
     */
    Stream<LlmChunk> stream(LlmRequest request);
}
