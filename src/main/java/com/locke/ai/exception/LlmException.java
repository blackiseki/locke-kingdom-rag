package com.locke.ai.exception;

/**
 * 大模型调用相关异常（网络、鉴权、限流、响应解析失败等）。
 *
 * <p>属于可降级异常：{@code ChatService} 捕获后可以返回兜底答案，
 * 而不是把 500 抛给前端。
 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 便于上层区分是否需要重试/切换模型。 */
    private final boolean retryable;

    public LlmException(String message) {
        this(message, null, false);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
