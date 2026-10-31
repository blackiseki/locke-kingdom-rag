package com.locke.ai.exception;

/**
 * 会话不存在。
 *
 * <p>单独一个异常类型，是为了能被 {@link GlobalExceptionHandler} 翻译成 404。
 * 如果直接抛 {@link IllegalArgumentException}，会走「参数不合法 → 400」那条路，
 * 把「这个会话 id 在库里查不到」误解成「你传的参数格式错了」。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("会话不存在: " + sessionId);
    }
}
