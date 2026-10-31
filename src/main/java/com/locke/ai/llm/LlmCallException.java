package com.locke.ai.llm;

import com.locke.ai.exception.LlmException;

import java.util.List;

/**
 * 所有候选 Provider 都失败时抛出，携带完整的尝试记录。
 *
 * <p>继承 {@link LlmException}，因此原有的 502 异常处理仍然生效；
 * 额外信息用于前端/日志解释「为什么最终没有答案」。
 */
public class LlmCallException extends LlmException {

    private final List<LlmAttempt> attempts;

    public LlmCallException(String message, List<LlmAttempt> attempts, Throwable cause) {
        super(message, cause, true);
        this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public LlmCallException(String message, List<LlmAttempt> attempts) {
        this(message, attempts, null);
    }

    public List<LlmAttempt> attempts() {
        return attempts;
    }
}
