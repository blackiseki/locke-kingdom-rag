package com.locke.ai.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理，统一返回 RFC 7807 的 {@link ProblemDetail}。
 *
 * <p>注意 catch-all 里对 {@link ErrorResponse} 的透传：Spring 自己的
 * 404 / 405 / 参数绑定失败都是 ErrorResponse，如果不透传就会被catch-all
 * 统统变成 500，这类 bug 很难查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** @Valid 校验失败：400，并列出所有字段错误。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("参数校验失败");
        return problem;
    }

    /** 入库失败：500。sourcePath 带出来方便定位是哪个文件。 */
    @ExceptionHandler(IngestException.class)
    public ProblemDetail handleIngest(IngestException ex) {
        log.error("入库失败, sourcePath={}", ex.getSourcePath(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "文档入库失败：" + ex.getMessage());
        problem.setTitle("入库失败");
        if (ex.getSourcePath() != null) {
            problem.setProperty("sourcePath", ex.getSourcePath());
        }
        return problem;
    }

    /**
     * 模型调用失败：502。
     *
     * <p>正常情况下 ChatService 会把 LlmException 降级成兜底答案，
     * 走到这里说明降级也失败了。
     */
    @ExceptionHandler(LlmException.class)
    public ProblemDetail handleLlm(LlmException ex) {
        log.error("大模型调用失败, retryable={}", ex.isRetryable(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "大模型调用失败：" + ex.getMessage());
        problem.setTitle("模型服务不可用");
        problem.setProperty("retryable", ex.isRetryable());
        return problem;
    }

    /** 非法参数（例如未知 provider）：400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("请求参数不合法");
        return problem;
    }

    /** 会话不存在：404（不是 400，参数格式没问题，是资源没找到）。 */
    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("会话不存在");
        return problem;
    }

    /** 兜底：其他未预期异常统一 500，但透传 Spring 自带的 ErrorResponse。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleOther(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        log.error("未处理异常", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请查看服务端日志");
        problem.setTitle("内部错误");
        return problem;
    }
}
