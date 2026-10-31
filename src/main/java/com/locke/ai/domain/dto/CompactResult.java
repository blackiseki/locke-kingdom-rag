package com.locke.ai.domain.dto;

/**
 * 手动压缩的结果。
 *
 * @param foldedMessages   本次被折叠进摘要的消息条数；0 表示没东西可压
 * @param summaryTokens    压缩后摘要的 token 估算
 * @param compressionCount 该会话累计压缩次数
 * @param compressed       是否真的压缩了
 */
public record CompactResult(int foldedMessages, int summaryTokens, int compressionCount, boolean compressed) {

    public static CompactResult nothing(int summaryTokens, int compressionCount) {
        return new CompactResult(0, summaryTokens, compressionCount, false);
    }
}
