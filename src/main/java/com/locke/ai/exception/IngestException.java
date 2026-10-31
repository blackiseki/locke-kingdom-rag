package com.locke.ai.exception;

/**
 * 文档入库异常（文件读不到、格式不对、写向量库失败等）。
 *
 * <p>单个文件解析失败一般只记 warning 不中断整批；
 * 只有整批无法开始（比如 data 目录不存在）才抛这个异常。
 */
public class IngestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 出问题的文件路径，可能为 null（整批失败时）。 */
    private final String sourcePath;

    public IngestException(String message) {
        this(message, null, null);
    }

    public IngestException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public IngestException(String message, Throwable cause, String sourcePath) {
        super(message, cause);
        this.sourcePath = sourcePath;
    }

    public String getSourcePath() {
        return sourcePath;
    }
}
